package playground.smithyql.parser

import cats.Defer
import cats.implicits._
import cats.parse.Numbers
import cats.parse.Parser
import cats.parse.Parser0
import cats.parse.Rfc5234
import playground.smithyql._

object Parsers {

  type T[+A] = WithSource[A]

  object tokens {
    import Parser._
    val comment: Parser[Comment] =
      string("//") *> charsWhile0(_ != '\n').map(Comment(_)) <* char('\n')

    val whitespace: Parser0[Unit] = charsWhile0(_.isWhitespace).void

    val comments: Parser0[List[Comment]] = comment
      .repSep0(whitespace)
      .surroundedBy(whitespace)

    val pos = Parser.index.map(Position(_))

    def mergeComments[A]: (((List[Comment], T[A]), List[Comment])) => T[A] = {
      case ((commentsBefore, v), commentsAfter) => v.withComments(commentsBefore, commentsAfter)
    }

    def mergeRange[
      A
    ]: (((Position, A), Position)) => T[A] = { case ((indexBefore, v), indexAfter) =>
      val range = SourceRange(indexBefore, indexAfter)

      WithSource.liftId(v).withRange(range)
    }

    def withComments[A](
      p: Parser[A],
      left: Boolean = true,
      right: Boolean = true,
    ): Parser[T[A]] = {

      val lhs =
        if (left)
          comments
        else
          Parser.pure(Nil)

      val rhs =
        if (right)
          comments
        else
          Parser.pure(Nil)

      (lhs.soft.with1 ~ withRange(p) ~ rhs).map(mergeComments)
    }

    def withComments0[A](
      p: Parser0[A]
    ): Parser0[T[A]] = (comments.soft ~ withRange0(p) ~ comments).map(mergeComments)

    def withRange[A](p: Parser[A]): Parser[T[A]] = (pos.with1 ~ p ~ pos).map(mergeRange)
    def withRange0[A](p: Parser0[A]): Parser0[T[A]] = (pos ~ p ~ pos).map(mergeRange)

    // A bit of a hack: replace the ranges of the given parser's WithSource
    // with ones containing the whole parser.
    // It's a short-term solution as the real one would involve adding new syntax nodes keeping the ranges.
    def expandRange0[S](p: Parser0[T[S]]): Parser0[T[S]] = tokens.withRange0(p).map { forRange =>
      forRange.value.withRange(forRange.range)
    }

    private[Parsers] val rawIdentifier = (Rfc5234.alpha ~ Parser.charsWhile0(_.isLetterOrDigit))
      .map { case (ch, s) => s.prepended(ch) }

    val identifier: Parser[String] =
      (Rfc5234.alpha ~ Parser.charsWhile0(ch => ch.isLetterOrDigit || "_".contains(ch)))
        .map { case (ch, s) => s.prepended(ch) }

    val number: Parser[String] = Numbers.jsonNumber

    val bool: Parser[Boolean] = string("true").as(true).orElse(string("false").as(false))

    // todo: allow quotes inside
    val stringLiteral: Parser[String] = anyChar
      .repUntil0(char('\"'))
      .map(_.mkString)
      .with1
      .surroundedBy(char('"'))

    val nullLiteral: Parser[Unit] = string("null")

    def punctuation(c: Char): Parser[Unit] = char(c)

    val colon = punctuation(':')
    val equalsSign = punctuation('=')
    val dot = punctuation('.')
    val comma = punctuation(',')
    val hash = punctuation('#')
    val openBrace = punctuation('{')
    val closeBrace = punctuation('}')

    val openBracket = punctuation('[')
    val closeBracket = punctuation(']')

  }

  object parsers {

    import Parser._

    val rawIdent: Parser[String] = tokens.identifier
    val ident: Parser[T[String]] = tokens.withComments(tokens.identifier)

    // doesn't accept comments
    val qualifiedIdent: Parser[QualifiedIdentifier] = {
      val segments =
        rawIdent.repSep(
          // soft: allows backtracking if dot isn't present (for operation names)
          tokens.whitespace.soft *> tokens.dot *> tokens.whitespace
        ) <* tokens.whitespace

      (
        // soft: allows backtracking if hash isn't present (for operation names)
        segments.soft ~ (tokens.hash *> tokens.whitespace *> rawIdent),
      ).map(QualifiedIdentifier.apply.tupled)
    }

    val useClause: Parser[UseClause[T]] = {
      string("use") *>
        string("service")
          .surroundedBy(tokens.whitespace) *>
        tokens.withRange(qualifiedIdent)
    }.map(UseClause.apply[T])

    val intLiteral = tokens.number.map(IntLiteral[T](_))

    val boolLiteral = tokens.bool.map(BooleanLiteral[T](_))

    val stringLiteral = tokens.stringLiteral.map(StringLiteral[T](_))
    val nullLiteral = tokens.nullLiteral.map(_ => NullLiteral[T]())

    lazy val node: Parser[InputNode[T]] = Parser.defer {
      intLiteral |
        boolLiteral |
        stringLiteral |
        nullLiteral |
        struct |
        listed
    }

    lazy val struct: Parser[Struct[T]] = {
      type TField = Binding[T]

      val field: Parser[TField] =
        (
          ident.map(_.map(Identifier.apply)) <* tokens.colon.orElse(tokens.equalsSign),
          tokens.withComments(node),
        ).mapN(Binding.apply[T])

      // field, then optional whitespace, then optional coma, then optionally more `fields`
      val fields: Parser0[Struct.Fields[T]] = field
        .repSep0WithTrailing(tokens.comma)
        .map(Struct.Fields[T](_))

      tokens
        .expandRange0(tokens.withComments0(fields))
        .with1
        .between(tokens.openBrace, tokens.closeBrace)
        .map(Struct.apply[T](_))
    }

    // this is mostly copy-pasted from structs, might not work lmao
    lazy val listed: Parser[Listed[T]] = {
      type TField = T[InputNode[T]]

      val field: Parser[TField] = tokens.withComments(node)

      // field, then optional whitespace, then optional coma, then optionally more `fields`
      val fields: Parser0[List[TField]] = field.repSep0WithTrailing(tokens.comma)

      tokens
        .expandRange0(tokens.withComments0(fields))
        .with1
        .between(tokens.openBracket, tokens.closeBracket)
        .map(Listed.apply[T](_))
    }

    val queryOperationName: Parser[QueryOperationName[T]] = {

      val serviceRef = tokens.withRange(qualifiedIdent <* tokens.whitespace) <* tokens.dot

      val operationName = tokens.withRange(rawIdent).map(_.map(OperationName[WithSource](_)))

      val sr = (serviceRef <* tokens.whitespace).?

      (
        sr.with1 ~
          operationName
      ).map(QueryOperationName.apply[WithSource].tupled)
    }

    val query: Parser[Query[T]] =
      (
        tokens.withComments(queryOperationName, left = false),
        tokens.withComments(struct, right = false),
      ).mapN {
        Query.apply(WithSource.liftId(Option.empty[UseClause[T]]), _, _)
      }

    val runQuery: Parser[RunQuery[T]] = tokens.withComments(query).map(RunQuery(_))

    val statement: Parser[Statement[T]] = runQuery

    val prelude = tokens.withComments(useClause).?.map(Prelude.apply)

    val sourceFile: Parser0[SourceFile[T]] = (prelude, tokens.withComments0(statement.rep0))
      .mapN(SourceFile.apply[T])

    implicit class ParserOps[A](parser: Parser[A]) {

      // like repSep0, but allows a trailing occurrence of the separator
      // if there's at least one occurrence of the main parser/
      def repSep0WithTrailing(
        sep: Parser[Unit]
      ): Parser0[List[A]] = Defer[Parser0].fix[List[A]] { self =>
        (parser ~ (sep *> self).orElse(Parser.pure(Nil)))
          .map { case (h, t) => h :: t }
          .orElse(Parser.pure(Nil))
      }

    }

  }

}
