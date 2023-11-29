import sbt._

import java.io.File

case class GenerateTupleSignals(
  sourceDir: File,
  from: Int,
  to: Int
) extends SourceGenerator(
  sourceDir / "scala" / "com" / "raquo" / "airstream" / "extensions" / s"TupleSignals.scala"
) {

  override def apply(): Unit = {
    line("package com.raquo.airstream.extensions")
    line()
    line("import com.raquo.airstream.core.Signal")
    line("import com.raquo.airstream.misc.MapSignal")
    line()
    line("// #Warning do not edit this file directly, it is generated by GenerateTupleSignals.scala")
    line()
    line("// These mapN helpers are implicitly available on signals of tuples")
    line()
    for (n <- from to to) {
      enter(s"class TupleSignal${n}[${tupleType(n)}](val signal: Signal[(${tupleType(n)})]) extends AnyVal {", "}") {
        line()
        enter(s"def mapN[Out](project: (${tupleType(n)}) => Out): Signal[Out] = {", "}") {
          enter(s"new MapSignal[(${tupleType(n)}), Out](", ")") {
            line("parent = signal,")
            line(s"project = v => project(${tupleType(n, "v._")}),")
            line(s"recover = None")
          }
        }
      }
      line()
      line("// --")
      line()
    }
  }
}
