package org.allenai.dictionary

import org.allenai.dictionary.patterns.NamedPattern

import java.text.ParseException
import java.util.regex.Pattern
import scala.util.control.NonFatal
import scala.util.parsing.combinator.RegexParsers
import scala.util.{ Failure, Success, Try }

sealed trait QExpr
sealed trait QLeaf extends QExpr
sealed trait QAtom extends QExpr {
  val qexpr: QExpr
}
sealed trait QCapture extends QAtom

case class QWord(value: String) extends QLeaf
case class QPos(value: String) extends QLeaf
case class QChunk(value: String) extends QLeaf
case class QDict(value: String) extends QLeaf
case class QNamedPattern(value: String) extends QLeaf
case class QPosFromWord(value: Option[String], wordValue: String, posTags: Map[String, Int])
  extends QLeaf
// Generalize a phrase to the nearest `pos` similar phrases
case class QGeneralizePhrase(qwords: Seq[QWord], pos: Int) extends QLeaf
case class SimilarPhrase(qwords: Seq[QWord], similarity: Double)
// A QGeneralizePhrase with its similar phrases pre-computed
case class QSimilarPhrases(qwords: Seq[QWord], pos: Int, phrases: Seq[SimilarPhrase])
    extends QLeaf {
  override def toString(): String = s"QSimilarPhrases(${qwords.map(_.value).mkString(" ")},$pos)"
}
case class QWildcard() extends QLeaf
case class QNamed(qexpr: QExpr, name: String) extends QCapture
case class QUnnamed(qexpr: QExpr) extends QCapture
case class QNonCap(qexpr: QExpr) extends QAtom
case class QSeq(qexprs: Seq[QExpr]) extends QExpr

sealed trait QRepeating extends QAtom {
  def min: Int
  def max: Int
}
case class QRepetition(qexpr: QExpr, min: Int, max: Int) extends QRepeating
case class QStar(qexpr: QExpr) extends QRepeating {
  def min: Int = 0
  def max: Int = -1
}
case class QPlus(qexpr: QExpr) extends QRepeating {
  def min: Int = 1
  def max: Int = -1
}
case object QSeq {
  def fromSeq(seq: Seq[QExpr]): QExpr = seq match {
    case expr :: Nil => expr
    case _ => QSeq(seq)
  }
}
case class QDisj(qexprs: Seq[QExpr]) extends QExpr
case object QDisj {
  def fromSeq(seq: Seq[QExpr]): QExpr = seq match {
    case expr :: Nil => expr
    case _ => QDisj(seq)
  }
}
case class QAnd(qexpr1: QExpr, qexpr2: QExpr) extends QExpr

object QExprParser extends RegexParsers {
  val posTagSet = Seq("PRP$", "NNPS", "WRB", "WP$", "WDT", "VBZ", "VBP", "VBN", "VBG", "VBD", "SYM",
    "RBS", "RBR", "PRP", "POS", "PDT", "NNS", "NNP", "JJS", "JJR", "WP", "VB", "UH", "TO", "RP",
    "RB", "NN", "MD", "LS", "JJ", "IN", "FW", "EX", "DT", "CD", "CC")
  val chunkTagSet = Seq("NP", "VP", "PP", "ADJP", "ADVP")
  val posTagRegex = posTagSet.map(Pattern.quote).mkString("|").r
  val chunkTagRegex = chunkTagSet.map(Pattern.quote).mkString("|").r
  // Turn off style---these are all just Parser[QExpr] definitions
  // scalastyle:off
  def integer = """-?[0-9]+""".r ^^ { _.toInt }
  def wordRegex = """(\\.|[^|\]\[\^(){}\s*+,"~])+""".r
  def word = wordRegex ^^ { x => QWord(x.replaceAll("""\\(.)""", """$1""")) }
  def generalizedWord = (word <~ "~") ~ integer ^^ { x =>
    QGeneralizePhrase(Seq(x._1), x._2)
  }
  def generalizedPhrase = ("\"" ~> rep1(word) <~ "\"") ~ ("~" ~> integer).? ^^ { x =>
    QGeneralizePhrase(x._1, x._2.getOrElse(0))
  }
  def pos = posTagRegex ^^ QPos
  def chunk = chunkTagRegex ^^ QChunk
  def dict = """\$[^$(){}\s*+|,]+""".r ^^ { s => QDict(s.tail) }
  def namedPattern = "#[a-zA-Z_]+".r ^^ { s => QNamedPattern(s.tail) }
  def wildcard = "\\.".r ^^^ QWildcard()
  def atom = wildcard | pos | chunk | dict | namedPattern | generalizedWord | generalizedPhrase | word
  def captureName = "?<" ~> """[A-z0-9]+""".r <~ ">"
  def named = "(" ~> captureName ~ expr <~ ")" ^^ { x => QNamed(x._2, x._1) }
  def unnamed = "(" ~> expr <~ ")" ^^ QUnnamed
  def nonCap = "(?:" ~> expr <~ ")" ^^ QNonCap
  def curlyDisj = "{" ~> repsep(expr, ",") <~ "}" ^^ QDisj.fromSeq
  def operand = named | nonCap | unnamed | curlyDisj | atom
  def repetition = (operand <~ "[") ~ ((integer <~ ",") ~ (integer <~ "]")) ^^ { x =>
    QRepetition(x._1, x._2._1, x._2._2)
  }
  def starred = operand <~ "*" ^^ QStar
  def plussed = operand <~ "+" ^^ QPlus
  def modified = starred | plussed | repetition
  def piece: Parser[QExpr] = modified | operand
  def branch = rep1(piece) ^^ QSeq.fromSeq
  def expr = repsep(branch, "|") ^^ QDisj.fromSeq
  def parse(s: String) = parseAll(expr, s)
  // scalastyle:on
}

// Use this so parser combinator objects are not in scope
object QueryLanguage {
  val parser = QExprParser
  def parse(
    s: String,
    allowCaptureGroups: Boolean = true
  ): Try[QExpr] = parser.parse(s) match {
    case parser.Success(result, _) =>
      Success(if (allowCaptureGroups) result else removeCaptureGroups(result))
    case parser.NoSuccess(message, next) =>
      val exception = new ParseException(message, next.pos.column)
      Failure(exception)
  }

  def removeCaptureGroups(expr: QExpr): QExpr = {
    expr match {
      case l: QLeaf => l
      case QSeq(children) => QSeq(children.map(removeCaptureGroups))
      case QDisj(children) => QDisj(children.map(removeCaptureGroups))
      case c: QCapture => QNonCap(c.qexpr)
      case QPlus(qexpr) => QPlus(removeCaptureGroups(qexpr))
      case QStar(qexpr) => QStar(removeCaptureGroups(qexpr))
      case QRepetition(qexpr, min, max) => QRepetition(removeCaptureGroups(qexpr), min, max)
      case QAnd(expr1, expr2) => QAnd(removeCaptureGroups(expr1), removeCaptureGroups(expr2))
      case QNonCap(qexpr) => QNonCap(removeCaptureGroups(qexpr))
    }
  }

  def interpolateTables(
    expr: QExpr,
    tables: Map[String, Table],
    patterns: Map[String, NamedPattern]
  ): Try[QExpr] = {
    def expandDict(value: String): QDisj = tables.get(value) match {
      case Some(table) if table.cols.size == 1 =>
        val rowExprs = for {
          row <- table.positive
          value <- row.values
          qseq = QSeq(value.qwords)
        } yield qseq
        QDisj(rowExprs)
      case Some(table) =>
        val name = table.name
        val ncol = table.cols.size
        throw new IllegalArgumentException(s"1-col table required: Table '$name' has $ncol columns")
      case None =>
        throw new IllegalArgumentException(s"Could not find table '$value'")
    }

    def expandNamedPattern(
      patternName: String,
      forbiddenPatternNames: Set[String] = Set.empty
    ): QExpr = {
      if (forbiddenPatternNames.contains(patternName)) {
        throw new IllegalArgumentException(s"Pattern $patternName recursively invokes itself.")
      }

      patterns.get(patternName) match {
        case Some(pattern) => try {
          recurse(parse(pattern.pattern, false).get, forbiddenPatternNames + patternName)
        } catch {
          case e if NonFatal(e) =>
            throw new IllegalArgumentException(
              s"While expanding pattern $patternName: ${e.getMessage}",
              e
            )
        }
        case None => throw new IllegalArgumentException(s"Could not find pattern '$patternName'")
      }
    }

    def recurse(expr: QExpr, forbiddenPatternNames: Set[String] = Set.empty): QExpr = expr match {
      case QDict(value) => expandDict(value)
      case QNamedPattern(value) => expandNamedPattern(value, forbiddenPatternNames)
      case l: QLeaf => l
      case QSeq(children) => QSeq(children.map(recurse(_, forbiddenPatternNames)))
      case QDisj(children) => QDisj(children.map(recurse(_, forbiddenPatternNames)))
      case QNamed(expr, name) => QNamed(recurse(expr, forbiddenPatternNames), name)
      case QNonCap(expr) => QNonCap(recurse(expr, forbiddenPatternNames))
      case QPlus(expr) => QPlus(recurse(expr, forbiddenPatternNames))
      case QStar(expr) => QStar(recurse(expr, forbiddenPatternNames))
      case QUnnamed(expr) => QUnnamed(recurse(expr, forbiddenPatternNames))
      case QAnd(expr1, expr2) =>
        QAnd(recurse(expr1, forbiddenPatternNames), recurse(expr2, forbiddenPatternNames))
      case QRepetition(expr, min, max) =>
        QRepetition(recurse(expr, forbiddenPatternNames), min, max)
    }
    Try(recurse(expr))
  }

  def interpolateSimilarPhrases(
    expr: QExpr,
    similarPhrasesSearcher: SimilarPhrasesSearcher
  ): Try[QExpr] = {
    def recurse(expr: QExpr): QExpr = expr match {
      case QGeneralizePhrase(phrase, pos) =>
        val similarPhrases =
          similarPhrasesSearcher.getSimilarPhrases(phrase.map(_.value).mkString(" "))
        QSimilarPhrases(phrase, pos, similarPhrases)
      case l: QLeaf => l
      case QSeq(children) => QSeq(children.map(recurse))
      case QDisj(children) => QDisj(children.map(recurse))
      case QNamed(expr, name) => QNamed(recurse(expr), name)
      case QNonCap(expr) => QNonCap(recurse(expr))
      case QPlus(expr) => QPlus(recurse(expr))
      case QStar(expr) => QStar(recurse(expr))
      case QUnnamed(expr) => QUnnamed(recurse(expr))
      case QAnd(expr1, expr2) => QAnd(recurse(expr1), recurse(expr2))
      case QRepetition(expr, min, max) => QRepetition(recurse(expr), min, max)
    }
    Try(recurse(expr))
  }

  /** Replaces QDict and QGeneralizePhrases expressions within a QExpr with
    * QDisj and QSimilarPhrase
    *
    * @param expr QExpr to interpolate
    * @param userEmail email of the user, used to find tables for dictionary expansions and named
    *                  patterns
    * @param similarPhrasesSearcher searcher to use when replacing QGeneralizePhrase expressions
    * @return the attempt to interpolated the query
    */
  def interpolateQuery(
    expr: QExpr,
    tables: Map[String, Table],
    patterns: Map[String, NamedPattern],
    similarPhrasesSearcher: SimilarPhrasesSearcher
  ): Try[QExpr] = {
    interpolateSimilarPhrases(interpolateTables(expr, tables, patterns).get, similarPhrasesSearcher)
  }

  /** Converts a query to its string format
    *
    * @param query query to evaluate
    * @return String representation of the query
    * @throws NotImplementedError if the query contains QAnd or QPosFromWord
    */
  def getQueryString(query: QExpr): String = {

    def recurse(qexpr: QExpr): String = qexpr match {
      case QWord(value) => value
      case QPos(value) => value
      case QChunk(value) => value
      case QDict(value) => "$" + value
      case QNamedPattern(value) => "#" + value
      case QWildcard() => "."
      case QSeq(children) => children.map(getQueryString).mkString(" ")
      case QDisj(children) => "{" + children.map(getQueryString).mkString(",") + "}"
      case QNamed(expr, name) => "(?<" + name + ">" + getQueryString(expr) + ")"
      case QUnnamed(expr) => "(" + getQueryString(expr) + ")"
      case QNonCap(expr) => "(?:" + getQueryString(expr) + ")"
      case QPlus(expr) => modifiableString(expr) + "+"
      case QStar(expr) => modifiableString(expr) + "*"
      case QRepetition(expr, min, max) => s"${modifiableString(expr)}[$min,$max]"
      case QGeneralizePhrase(phrase, pos) =>
        if (phrase.size == 1) {
          s"${recurse(phrase.head)}~$pos"
        } else {
          // Use triple quote syntax since scala's single quote interpolation has a bug with \"
          s""""${phrase.map(recurse).mkString(" ")}"~$pos"""
        }
      case QSimilarPhrases(phrase, pos, _) =>
        if (phrase.size == 1) {
          s"${recurse(phrase.head)}~$pos"
        } else {
          // Use triple quote syntax since scala's single quote interpolation has a bug with \"
          s""""${phrase.map(recurse).mkString(" ")}"~$pos"""
        }
      case _ => ???
    }

    def modifiableString(qexpr: QExpr): String = qexpr match {
      case _: QLeaf | _: QCapture | _: QDisj | _: QNonCap => recurse(qexpr)
      case _ => "(?:" + recurse(qexpr) + ")"
    }

    recurse(query)
  }

  /** @param qexpr query expression to evaluate
    * @return All capture groups that are present in the query
    */
  def getCaptureGroups(qexpr: QExpr): Seq[QCapture] = qexpr match {
    case q: QCapture => Seq(q)
    case q: QAtom => getCaptureGroups(q.qexpr)
    case q: QLeaf => Seq()
    case QSeq(children) => children.flatMap(getCaptureGroups)
    case QDisj(children) => children.flatMap(getCaptureGroups)
    case QAnd(expr1, expr2) => getCaptureGroups(expr1) ++ getCaptureGroups(expr2)
  }

  /** @param qexpr query to evaluate
    * @return range of tokens the query will match, ends with -1 if the query
    * can match a variable number of tokens'
    */
  def getQueryLength(qexpr: QExpr): (Int, Int) = qexpr match {
    case QDict(_) => (1, -1)
    case QNamedPattern(_) => (1, -1)
    case QGeneralizePhrase(_, _) => (1, -1)
    case QSimilarPhrases(qwords, pos, phrases) =>
      val lengths = qwords.size +: phrases.slice(0, pos).map(_.qwords.size)
      (lengths.min, lengths.max)
    case l: QLeaf => (1, 1)
    case qr: QRepeating => {
      val (baseMin, baseMax) = getQueryLength(qr.qexpr)
      val max = if (baseMax == -1 || qr.max == -1) -1 else baseMax * qr.max
      (baseMin * qr.min, max)
    }
    case QSeq(seq) =>
      val (mins, maxes) = seq.map(getQueryLength).unzip
      val max = if (maxes contains -1) -1 else maxes.sum
      (mins.sum, max)
    case QDisj(seq) =>
      val (mins, maxes) = seq.map(getQueryLength).unzip
      val max = if (maxes contains -1) -1 else maxes.max
      (mins.min, max)
    case QAnd(q1, q2) =>
      val (min1, max1) = getQueryLength(q1)
      val (min2, max2) = getQueryLength(q2)
      (Math.min(min1, min2), math.max(max1, max2))
    case q: QAtom => getQueryLength(q.qexpr)
  }

  /** Convert a QRepetition to a QStar or QPlus if possible, None if it can be deleted */
  def convertRepetition(qexpr: QRepetition): Option[QExpr] = qexpr match {
    case QRepetition(expr, 0, -1) => Some(QStar(expr))
    case QRepetition(expr, 1, -1) => Some(QPlus(expr))
    case QRepetition(expr, 1, 1) => Some(expr)
    case QRepetition(expr, 0, 0) => None
    case _ => Some(qexpr)
  }
}
