package io.hydrosphere.mist

import org.scalatest.{FunSpec, Matchers}

class ExamplesSpecSpark extends FunSpec with MistItTest with Matchers {
    val interface = MistHttpInterface("localhost", 2004)

    it("run simple context") {
      val result = interface.runJob("simple-context",
          "numbers" -> List(1, 2, 3),
          "multiplier" -> 2
      )

      result.success shouldBe true
    }

}
