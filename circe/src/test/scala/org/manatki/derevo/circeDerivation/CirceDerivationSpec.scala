package org.manatki.derevo.circeDerivation

import io.circe.Encoder
import org.scalatest.FlatSpec
import org.manatki.derevo.derive
import io.circe.syntax._
import io.circe.parser._
import io.circe.derivation.renaming

class CirceDerivationSpec extends FlatSpec {
  "Circe derivation" should "derive simple codecs" in {
    @derive(encoder, decoder)
    final case class Foo (string: String, int: Int)

    val foo = Foo("kek", -42)
    val fooJson = """{"string":"kek","int":-42}"""

    assert(foo.asJson.noSpaces == fooJson)
    assert(decode[Foo](fooJson) == Right(foo))
  }

  it should "derive codecs with case transformation" in {
    @derive(decoder(renaming.snakeCase, false, None), encoder(renaming.kebabCase, None))
    final case class Bar(stringName: String, integerAge: Int)

    val decodedBar = decode[Bar](
      """
        |{
        |   "string_name": "Cheburek",
        |   "integer_age": 146
        |}
        |""".stripMargin)

    assert(decodedBar == Right(Bar("Cheburek", 146)))
    val encodedBar =
      """
        |{
        |   "string-name": "Lolkek",
        |   "integer-age": -228
        |}
        |""".stripMargin.filterNot(_.isWhitespace)
    assert(Bar("Lolkek", -228).asJson.noSpaces == encodedBar)
  }

  it should "derive codecs with type discriminator" in {
    val barJson =
      """
        |{
        |  "bar": 123,
        |  "type": "Bar"
        |}
        |""".stripMargin.filterNot(_.isWhitespace)
    val bar = SealedTrait.Bar(123)

    val bazJson =
      """
        |{
        |  "baz": "nani",
        |  "type": "Baz"
        |}
        |""".stripMargin.filterNot(_.isWhitespace)
    val baz = SealedTrait.Baz("nani")

    val encode = Encoder[SealedTrait].apply _
    assert(encode(bar).noSpaces == barJson)
    assert(encode(baz).noSpaces == bazJson)

    assert(decode[SealedTrait](barJson) == Right(bar))
    assert(decode[SealedTrait](bazJson) == Right(baz))
  }
}

@derive(encoder(identity, Some("type")), decoder(identity, false, Some("type")))
sealed trait SealedTrait

object SealedTrait {
  @derive(encoder, decoder)
  case class Bar(bar: Int) extends SealedTrait

  @derive(encoder, decoder)
  case class Baz(baz: String) extends SealedTrait
}