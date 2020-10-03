package dotty.dokka.tasty.comments

import scala.jdk.CollectionConverters._
import scala.tasty.Reflection

import org.jetbrains.dokka.model.{doc => dkkd}
import com.vladsch.flexmark.{ast => mda}
import com.vladsch.flexmark.util.{ast => mdu}
import com.vladsch.flexmark.ext.gfm.{tables => mdt}
import com.vladsch.flexmark.ext.{wikilink => mdw}

import dotty.dokka.tasty.SymOps

class MarkdownConverter(val repr: Repr) extends BaseConverter {
  import Emitter._

  // makeshift support for not passing an owner
  // see same in wiki.Converter
  val r: repr.r.type = if repr == null then null else repr.r
  val owner: r.Symbol = if repr == null then null.asInstanceOf[r.Symbol] else repr.sym

  object SymOps extends SymOps[r.type](r)
  import SymOps._

  def convertDocument(doc: mdu.Document): dkkd.DocTag = {
    val res = collect {
      doc.getChildIterator.asScala.foreach(emitConvertedNode)
    }

    dkkd.P(res.asJava, kt.emptyMap)
  }

  def convertChildren(n: mdu.Node): Seq[dkkd.DocTag] =
    collect {
      n.getChildIterator.asScala.foreach(emitConvertedNode)
    }

  def emitConvertedNode(n: mdu.Node)(using Emitter[dkkd.DocTag]): Unit = n match {
    case n: mda.Paragraph =>
      emit(dkkd.P(convertChildren(n).asJava, kt.emptyMap))

    case n: mda.Heading => emit(n.getLevel match {
        case 1 => dkkd.H1(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
        case 2 => dkkd.H2(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
        case 3 => dkkd.H3(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
        case 4 => dkkd.H4(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
        case 5 => dkkd.H5(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
        case 6 => dkkd.H6(List(dkk.text(n.getText().toString)).asJava, kt.emptyMap)
    })

    case n: mda.Text => emit(dkk.text(n.getChars.toString))
    // case n: mda.HtmlInline => dkkd.Br.INSTANCE
    case n: mda.Emphasis =>
      // TODO doesn't actually show up in output, why?
      emit(n.getOpeningMarker.toString match {
        case "*" => dkkd.B(convertChildren(n).asJava, kt.emptyMap)
        case "_" => dkkd.I(convertChildren(n).asJava, kt.emptyMap)
      })

    case n: mda.StrongEmphasis =>
      // TODO doesn't actually show up in output, why?
      // TODO distinguish between strong and regular emphasis?
      emit(n.getOpeningMarker.toString match {
        case "**" => dkkd.B(convertChildren(n).asJava, kt.emptyMap)
        case "__" => dkkd.I(convertChildren(n).asJava, kt.emptyMap)
      })

    case n: mda.Link =>
      val body: String = n.getText.toString
      val target: String = n.getUrl.toString
      def resolveBody(default: String) =
        val resolved = if !body.isEmpty then body else default
        List(dkk.text(resolved)).asJava

      emit(dkkd.A(resolveBody(default = target), Map("href" -> target).asJava))

    case n: mdw.WikiLink =>
      val (target, body) =
        val chars = n.getChars.toString.substring(2, n.getChars.length - 2)
        MarkdownConverter.splitWikiLink(chars)

      def resolveBody(default: String) =
        val resolved = if !body.isEmpty then body else default
        List(dkk.text(resolved)).asJava

      emit(target match {
        case SchemeUri() =>
          dkkd.A(resolveBody(default = target), Map("href" -> target).asJava)
        case _ =>
          resolveLinkQuery(target, body)
      })

    case n: mda.Code =>
      emit(dkkd.CodeInline(convertChildren(n).asJava, kt.emptyMap))
    case n: mda.IndentedCodeBlock =>
      val bld = new StringBuilder
      n.getContentLines.asScala.foreach(bld append _)
      emit(dkkd.CodeBlock(List(dkk.text(bld.toString)).asJava, kt.emptyMap))
    case n: mda.FencedCodeBlock =>
      // n.getInfo - where to stick this?
      emit(dkkd.CodeBlock(convertChildren(n).asJava, kt.emptyMap))

    case n: mda.ListBlock =>
      val c = convertChildren(n).asJava
      emit(n match {
        case _: mda.OrderedList => dkkd.Ol(c, kt.emptyMap)
        case _ => dkkd.Ul(c, kt.emptyMap)
      })
    case n: mda.ListItem =>
      emit(dkkd.Li(convertChildren(n).asJava, kt.emptyMap))

    case n: mda.BlockQuote =>
      emit(dkkd.BlockQuote(convertChildren(n).asJava, kt.emptyMap))

    case n: mdt.TableBlock =>
      // the structure is:
      // TableBlock {
      //   TableHeader {
      //     TableRow {
      //       TableCell { ... }
      //       TableCell { ... }
      //     }
      //   }
      //   TableSeparator { TableRow { ... } }
      //   TableBody { TableRow { ... } ... }
      // }
      val header =
        n.getFirstChild.getChildIterator.asScala.map { nn =>
          dkkd.Tr(
            nn.getChildIterator.asScala.map { nnn =>
              dkkd.Th(convertChildren(nnn).asJava, kt.emptyMap)
            }.toSeq.asJava,
            kt.emptyMap
          )
        }

      val body =
        n.getChildIterator.asScala.drop(2).next.getChildIterator.asScala.map { nn =>
          dkkd.Tr(
            nn.getChildIterator.asScala.map { nnn =>
              dkkd.Td(convertChildren(nnn).asJava, kt.emptyMap)
            }.toSeq.asJava,
            kt.emptyMap
          )
        }

      emit(dkkd.Table(
        (header ++ body).toSeq.asJava,
        kt.emptyMap
      ))

    case _: mda.SoftLineBreak => emit(dkkd.Br.INSTANCE)

    case _ =>
      println(s"!!! DEFAULTING @ ${n.getNodeName}")
      emit(dkkd.P(
        List(
          dkkd.Span(
            List(dkk.text(s"!!! DEFAULTING @ ${n.getNodeName}")).asJava,
            kt.emptyMap,
          ),
          dkk.text(MarkdownParser.renderToText(n))
        ).asJava,
        kt.emptyMap
      ))
  }

  def extractAndConvertSummary(doc: mdu.Document): Option[dkkd.DocTag] =
    doc.getChildIterator.asScala.collectFirst { case p: mda.Paragraph =>
      dkkd.P(convertChildren(p).asJava, kt.emptyMap)
    }

  def resolveLinkQuery(queryStr: String, body: String): dkkd.DocTag = {
    def resolveBody(default: String) =
      val resolved = if !body.isEmpty then body else default
      List(dkk.text(resolved)).asJava

    withParsedQuery(queryStr) { query =>
      MemberLookup.lookup(using r)(query, owner) match {
        case Some((sym, targetText)) =>
          dkkd.DocumentationLink(sym.dri, resolveBody(default = targetText), kt.emptyMap)
        case None =>
          dkkd.A(resolveBody(default = query.join), Map("href" -> "#").asJava)
      }
    }
  }
}

object MarkdownConverter {
  def splitWikiLink(chars: String): (String, String) =
    // split on a space which is not backslash escaped (regex uses "zero-width negative lookbehind")
    chars.split("(?<!\\\\) ", /*max*/ 2) match {
      case Array(target) => (target, "")
      case Array(target, userText) => (target, userText)
    }
}
