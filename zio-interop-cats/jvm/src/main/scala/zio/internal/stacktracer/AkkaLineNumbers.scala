/*
 * Copyright (C) 2014-2021 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.internal.stacktracer

import java.io.DataInputStream
import java.io.InputStream
import java.lang.invoke.SerializedLambda
import scala.annotation.switch
import scala.util.control.NonFatal

/**
 * This parser has been copied from the `akka-typed` project and modified,
 * as always these guys are way ahead of us! ;)
 *
 * https://github.com/akka/akka/blob/4729a80e7e6f2923a7e6369b21b2693f82c64589/akka-actor/src/main/scala/akka/util/LineNumbers.scala
 *
 * This is a minimized byte-code parser that concentrates exclusively on line
 * numbers and source file extraction. It works for all normal classes up to
 * format 52:0 (JDK8), and it also works for Lambdas that are Serializable. The
 * latter restriction is due to the fact that the proxy object generated by
 * LambdaMetafactory otherwise contains no information about which method backs
 * this particular lambda (and there might be multiple defined within a single
 * class).
 */
private[stacktracer] object AkkaLineNumbers {

  sealed abstract class Result
  case object NoSourceInfo                                  extends Result
  final case class UnknownSourceFormat(explanation: String) extends Result
  final case class SourceFile(filename: String)             extends Result {
    override def toString = filename
  }
  final case class SourceFileLines(filename: String, from: Int, to: Int, className: String, methodName: String)
      extends Result {
    override def toString: String = if (from != to) s"$filename:$from-$to" else s"$filename:$from"
  }

  /**
   * Scala API: Obtain line number information for the class defining the given object.
   * This is done by reading the byte code (a potentially blocking IO operation)
   * and interpreting the debug information that it may contain.
   *
   * This does not work for Java 8 lambdas that are not Serializable, because
   * the language designers have consciously made it impossible to obtain the
   * byte code for those.
   */
  // FIXME: this needs memoization with an LRU cache
  final def apply(obj: AnyRef): Result =
    getStreamForLambda(obj) orElse getStreamForClass(obj.getClass) match {
      case Some((stream, className, methodName)) => getInfo(stream, className, methodName)
      case None                                  => NoSourceInfo
    }

  /*
   * IMPLEMENTATION
   */

  // compile-time constant; conditionals below will be elided if false
  private[this] final val debug = false

  private[this] final class Constants(count: Int) {

    private[this] var _fwd  = Map.empty[Int, String]
    private[this] var _rev  = Map.empty[String, Int]
    private[this] var _xref = Map.empty[Int, Int]

    def fwd: Map[Int, String] = _fwd
    def rev: Map[String, Int] = _rev

    private[this] var nextIdx = 1

    def isDone: Boolean = nextIdx >= count

    def apply(idx: Int): String = _fwd(idx)
    def apply(str: String): Int = _rev(str)

    def resolve(): Unit                = _xref.foreach(p => put(p._1, apply(p._2)))
    def contains(str: String): Boolean = _rev contains str

    private[this] def put(idx: Int, str: String): Unit = {
      if (!(_rev contains str)) _rev = _rev.updated(str, idx)
      _fwd = _fwd.updated(idx, str)
    }

    def readOne(d: DataInputStream): Unit =
      (d.readByte(): @switch) match {
        case 1  => // Utf8
          val str = d.readUTF()
          put(nextIdx, str)
          nextIdx += 1
        case 3  => // Integer
          skip(d, 4)
          nextIdx += 1
        case 4  => // Float
          skip(d, 4)
          nextIdx += 1
        case 5  => // Long
          skip(d, 8)
          nextIdx += 2
        case 6  => // Double
          skip(d, 8)
          nextIdx += 2
        case 7  => // Class
          val other = d.readUnsignedShort()
          _xref = _xref.updated(nextIdx, other)
          nextIdx += 1
        case 8  => // String
          skip(d, 2)
          nextIdx += 1
        case 9  => // FieldRef
          skip(d, 4) // two shorts
          nextIdx += 1
        case 10 => // MethodRef
          skip(d, 4) // two shorts
          nextIdx += 1
        case 11 => // InterfaceMethodRef
          skip(d, 4) // two shorts
          nextIdx += 1
        case 12 => // NameAndType
          skip(d, 4) // two shorts
          nextIdx += 1
        case 15 => // MethodHandle
          skip(d, 3) // a byte and a short
          nextIdx += 1
        case 16 => // MethodType
          skip(d, 2)
          nextIdx += 1
        case 18 => // InvokeDynamic
          skip(d, 4) // two shorts
          nextIdx += 1
      }

  }

  private[this] def getInfo(stream: InputStream, className: String, methodName: Option[String]): Result = {
    val dis = new DataInputStream(stream)

    try {
      skipID(dis)
      skipVersion(dis)
      implicit val constants = getConstants(dis)
      if (debug)
        println(s"LNB:   fwd(${constants.fwd.size}) rev(${constants.rev.size}) ${constants.fwd.keys.toList.sorted}")
      skipClassInfo(dis)
      skipInterfaceInfo(dis)
      skipFields(dis)
      val lines              = readMethods(dis, methodName)
      val source             = readAttributes(dis)

      if (source.isEmpty) NoSourceInfo
      else
        lines match {
          case None             =>
            SourceFile(source.get)
          case Some((from, to)) =>
            SourceFileLines(source.get, from, to, className, methodName.getOrElse("apply"))
        }

    } catch {
      case ex if NonFatal(ex) => UnknownSourceFormat(s"parse error: ${ex.getMessage}")
    } finally
      try dis.close()
      catch {
        case ex: InterruptedException => throw ex
        case NonFatal(_)              => // ignore
      }
  }

  private[this] def getStreamForClass(c: Class[?]): Option[(InputStream, String, None.type)] =
    try {
      val name     = c.getName
      val resource = name.replace('.', '/') + ".class"
      val cl       = c.getClassLoader
      val r        = if (cl ne null) cl.getResourceAsStream(resource) else null
      if (debug) println(s"LNB:     resource '$resource' resolved to stream $r")
      if (r ne null) Some((r, name, None)) else None
    } catch {
      case ex if NonFatal(ex) =>
        if (debug) ex.printStackTrace()
        None
    }

  private[this] def getStreamForLambda(l: AnyRef): Option[(InputStream, String, Some[String])] =
    try {
      val c            = l.getClass
      val writeReplace = c.getDeclaredMethod("writeReplace")
      writeReplace.setAccessible(true)
      writeReplace.invoke(l) match {
        case serialized: SerializedLambda =>
          if (debug)
            println(s"LNB:     found Lambda implemented in ${serialized.getImplClass}:${serialized.getImplMethodName}")
          val r = c.getClassLoader.getResourceAsStream(serialized.getImplClass + ".class")
          if (r ne null) Some((r, serialized.getImplClass, Some(serialized.getImplMethodName))) else None
        case _                            => None
      }
    } catch {
      case ex if NonFatal(ex) =>
        if (debug) ex.printStackTrace()
        None
    }

  private[this] def skipID(d: DataInputStream): Unit = {
    val magic = d.readInt()
    if (debug) println(f"LNB: magic=0x$magic%08X")
    if (magic != 0xcafebabe) throw new IllegalArgumentException("not a Java class file")
  }

  private[this] def skipVersion(d: DataInputStream): Unit = {
    val minor = d.readShort()
    val major = d.readShort()
    if (debug) println(s"LNB: version=$major:$minor")
  }

  private[this] def getConstants(d: DataInputStream): Constants = {
    val count = d.readUnsignedShort()
    if (debug) println(s"LNB: reading $count constants")
    val c     = new Constants(count)
    while (!c.isDone) c.readOne(d)
    c.resolve()
    c
  }

  private[this] def skipClassInfo(d: DataInputStream)(implicit c: Constants): Unit = {
    skip(d, 2) // access flags
    val name = d.readUnsignedShort() // class name
    skip(d, 2) // superclass name
    if (debug) println(s"LNB: class name = ${c(name)}")
  }

  private[this] def skipInterfaceInfo(d: DataInputStream)(implicit c: Constants): Unit = {
    val count = d.readUnsignedShort()
    var i     = 1
    while (i <= count) {
      i += 1
      val intf = d.readUnsignedShort()
      if (debug) println(s"LNB:   implements ${c(intf)}")
    }
  }

  private[this] def skipFields(d: DataInputStream)(implicit c: Constants): Unit = {
    val count = d.readUnsignedShort()
    if (debug) println(s"LNB: reading $count fields:")
    var i     = 1
    while (i <= count) {
      i += 1
      skipMethodOrField(d)
    }
  }

  private[this] def skipMethodOrField(d: DataInputStream)(implicit c: Constants): Unit = {
    skip(d, 2) // access flags
    val name = d.readUnsignedShort() // name
    skip(d, 2) // signature
    val attributes = d.readUnsignedShort()
    var i          = 1
    while (i <= attributes) {
      i += 1
      skipAttribute(d)
    }
    if (debug) println(s"LNB:   ${c(name)} ($attributes attributes)")
  }

  private[this] def skipAttribute(d: DataInputStream): Unit = {
    skip(d, 2) // tag
    val length = d.readInt()
    skip(d, length)
  }

  private[this] def readMethods(d: DataInputStream, methodName: Option[String])(implicit
    c: Constants
  ): Option[(Int, Int)] = {
    val count = d.readUnsignedShort()
    if (debug) println(s"LNB: reading $count methods")
    if (c.contains("Code") && c.contains("LineNumberTable"))
      (1 to count)
        .flatMap(_ => readMethod(d, c("Code"), c("LineNumberTable"), methodName).toList)
        .foldLeft(Int.MaxValue -> 0) { case ((low, high), (start, end)) =>
          (Math.min(low, start), Math.max(high, end))
        } match {
        case (Int.MaxValue, 0) => None
        case other             => Some(other)
      }
    else {
      if (debug) println(s"LNB:   (skipped)")
      var i = 1
      while (i <= count) {
        i += 1
        skipMethodOrField(d)
      }
      None
    }
  }

  private[this] def readMethod(d: DataInputStream, codeTag: Int, lineNumberTableTag: Int, methodName: Option[String])(
    implicit c: Constants
  ): Option[(Int, Int)] = {
    skip(d, 2) // access flags
    val name = d.readUnsignedShort() // name
    skip(d, 2) // signature
    if (debug) println(s"LNB:   ${c(name)}")
    val attributes =
      for (_ <- 1 to d.readUnsignedShort()) yield {
        val tag    = d.readUnsignedShort()
        val length = d.readInt()
        if (tag != codeTag || (methodName.isDefined && c(name) != methodName.get)) {
          skip(d, length)
          None
        } else {
          skip(d, 4)           // shorts: max stack, max locals
          skip(d, d.readInt()) // skip byte-code
          // skip exception table: N records of 4 shorts (start PC, end PC, handler PC, catch type)
          skip(d, 8 * d.readUnsignedShort())
          val possibleLines =
            for (_ <- 1 to d.readUnsignedShort()) yield {
              val tag    = d.readUnsignedShort()
              val length = d.readInt()
              if (tag != lineNumberTableTag) {
                skip(d, length)
                None
              } else {
                val lines =
                  for (_ <- 1 to d.readUnsignedShort()) yield {
                    skip(d, 2)            // start PC
                    d.readUnsignedShort() // finally: the line number
                  }
                Some(lines.min -> lines.max)
              }
            }
          if (debug) println(s"LNB:     nested attributes yielded: $possibleLines")
          possibleLines.flatten.headOption
        }
      }
    attributes.find(_.isDefined).flatten
  }

  private[this] def readAttributes(d: DataInputStream)(implicit c: Constants): Option[String] = {
    val count = d.readUnsignedShort()
    if (debug) println(s"LNB: reading $count attributes")
    if (c contains "SourceFile") {
      val s          = c("SourceFile")
      val attributes =
        for (_ <- 1 to count) yield {
          val tag    = d.readUnsignedShort()
          val length = d.readInt()
          if (debug) println(s"LNB:   tag ${c(tag)} ($length bytes)")
          if (tag != s) {
            skip(d, length)
            None
          } else {
            val name = d.readUnsignedShort()
            Some(c(name))
          }
        }
      if (debug) println(s"LNB:   yielded $attributes")
      attributes.flatten.headOption
    } else {
      if (debug) println(s"LNB:   (skipped)")
      None
    }
  }

  private[this] def skip(d: DataInputStream, length: Int): Unit =
    if (d.skipBytes(length) != length) throw new IllegalArgumentException("class file ends prematurely")

}
