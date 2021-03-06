package org.allenai.ike.ml.queryop

import org.allenai.common.testkit.UnitSpec
import org.allenai.ike.ml._
import org.allenai.ike.{ QPos, QWord }

import scala.collection.immutable.IntMap

class TestSpecifyOpGenerator extends UnitSpec {

  "getRepeatedOpMatch" should "Create correct repeated ops" in {
    val matches = QueryMatches(QuerySlotData(
      Some(QWord("d")), QueryToken(1), true
    ), Seq(
      QueryMatch(Seq(Token("b", "NN"), Token("b", "NN")), true),
      QueryMatch(Seq(Token("a", "NN")), true)
    ))

    val leafGen = QLeafGenerator(true, true)

    val rOps = SpecifyingOpGenerator.getRepeatedOpMatch(matches, leafGen)
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 1, QWord("b"))))
    assertResult(IntMap(1 -> 0))(rOps(SetRepeatedToken(1, 1, QWord("a"))))
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 2, QWord("b"))))
    assertResult(IntMap(0 -> 0, 1 -> 0))(rOps(SetRepeatedToken(1, 1, QPos("NN"))))
    assertResult(IntMap(0 -> 0))(rOps(SetRepeatedToken(1, 2, QPos("NN"))))
    assertResult(rOps.size)(5)
  }
}
