package derevo

import scala.language.higherKinds
import scala.reflect.macros.blackbox

private trait Dummy1[X]
private trait Dummy2[X[_]]
private trait Dummy3[X[_[_]]]

class Derevo(val c: blackbox.Context) {
  import c.universe._
  val DelegatingSymbol = typeOf[delegating].typeSymbol
  val PhantomSymbol    = typeOf[phantom].typeSymbol

  val IsDerivation         = isInstanceDef[Derivation[Dummy1]]()
  val IsSpecificDerivation = isInstanceDef[PolyDerivation[Dummy1, Dummy1]]()
  val IsDerivationK1       = isInstanceDef[DerivationK1[Dummy2]](1)
  val IsDerivationK2       = isInstanceDef[DerivationK2[Dummy3]](1)
  val IIH                  = weakTypeOf[InjectInstancesHere].typeSymbol

  def delegate[TC[_], I]: c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, None))

  def delegateK1[TC[_[_]], I[_]]: c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, None))

  def delegateK2[TC[_[_[_]]], I[_[_]]]: c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, None))

  def delegateParam[TC[_], I, Arg](arg: c.Expr[Arg]): c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, Some(method => q"$method($arg)")))

  def delegateParams2[TC[_], I, Arg1, Arg2](arg1: c.Expr[Arg1], arg2: c.Expr[Arg2]): c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, Some(method => q"$method($arg1, $arg2)")))

  def delegateParams3[TC[_], I, Arg1, Arg2, Arg3](arg1: c.Expr[Arg1], arg2: c.Expr[Arg2], arg3: c.Expr[Arg3]): c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, Some(method => q"$method($arg1, $arg2, $arg3)")))

  private def unpackArgs(args: Tree): Seq[Tree] =
    args match {
      case q"(..$params)" => params
      case _ => abort("argument of delegateParams must be tuple")
    }

  def delegateParams[TC[_], I, Args](args: c.Expr[Args]): c.Expr[TC[I]] =
    c.Expr(delegation(c.prefix.tree, Some(method => q"$method(..${unpackArgs(args.tree)})")))

  private def delegation(tree: Tree, maybeCall: Option[Tree => Tree]): Tree = {
    val annots = tree.tpe.termSymbol.annotations
    val s = annots
      .map(_.tree)
      .collectFirst {
        case q"new $cls(${to: Tree})" if cls.symbol == DelegatingSymbol =>
          c.eval(c.Expr[String](to))
      }
      .getOrElse(abort(s"could not find @delegating annotation at $tree"))

    val method = s.split("\\.").map(TermName(_)).foldLeft[Tree](q"_root_")((a, b) => q"$a.$b")

    maybeCall.fold(method)(call => call(method))
  }

  def deriveMacro(annottees: Tree*): Tree = {
    annottees match {
      case Seq(cls: ClassDef) =>
        q"""
           $cls
           object ${cls.name.toTermName} {
               ..${instances(cls)}
           }
         """

      case Seq(
          cls: ClassDef,
          q"$mods object $companion extends {..$earlyDefs} with ..$parents{$self => ..$defs}"
          ) =>
        q"""
           $cls
           $mods object $companion extends {..$earlyDefs} with ..$parents{$self =>
             ..${injectInstances(defs, instances(cls))}
           }
         """
    }
  }

  private def injectInstances(defs: Seq[Tree], instances: List[Tree]): Seq[Tree] = {
    val (pre, post) = defs.span {
      case tree @ q"$call()" =>
        call match {
          case q"insertInstancesHere"    => false
          case q"$_.insertInstancesHere" => false
          case _                         => true
        }
      case _ => true
    }

    pre ++ instances ++ post.drop(1)
  }

  private def instances(cls: ClassDef): List[Tree] =
    c.prefix.tree match {
      case q"new derive(..${instances})" =>
        instances
          .map(buildInstance(_, cls))
    }

  private def buildInstance(tree: Tree, cls: ClassDef): Tree = {
    val typName = TypeName(cls.name.toString)

    val (name, fromTc, toTc, drop, call) = tree match {
      case q"$obj(..$args)" =>
        val (name, from, to, drop) = nameAndTypes(obj)
        (name, from, to, drop, tree)

      case q"$obj.$method($args)" =>
        val (name, from, to, drop) = nameAndTypes(obj)
        (name, from, to, drop, tree)

      case q"$obj" =>
        val (name, from, to, drop) = nameAndTypes(obj)
        (name, from, to, drop, q"$obj.instance")
    }

    val tn = TermName(name)
    if (cls.tparams.isEmpty) {
      val resT = mkAppliedType(toTc, tq"$typName")
      q"implicit val $tn: $resT = $call"
    } else {
      val tparams = cls.tparams.drop(drop)
      val implicits = tparams.flatMap { tparam =>
        val phantom =
          tparam.mods.annotations.exists { t =>
            c.typecheck(t).tpe.typeSymbol == PhantomSymbol
          }
        if (phantom) None
        else {
          val name = TermName(c.freshName("ev"))
          val typ  = tparam.name
          val reqT = mkAppliedType(fromTc, tq"$typ")
          Some(q"val $name: $reqT")
        }
      }
      val tps    = tparams.map(_.name)
      val appTyp = tq"$typName[..$tps]"
      val resT   = mkAppliedType(toTc, appTyp)
      q"implicit def $tn[..$tparams](implicit ..$implicits): $resT = $call"
    }
  }

  private def mkAppliedType(tc: Type, arg: Tree): Tree = tc match {
    case TypeRef(pre, sym, ps) => tq"$sym[..$ps, $arg]"
    case _                     => tq"$tc[$arg]"
  }

  private def nameAndTypes(obj: Tree): (String, Type, Type, Int) = {
    val name = obj match {
      case Ident(name) => c.freshName(name.toString)
    }

    val (from, to, drop) = c.typecheck(obj).tpe match {
      case IsDerivation(f, t, d)         => (f, t, d)
      case IsSpecificDerivation(f, t, d) => (f, t, d)
      case IsDerivationK1(f, t, d)       => (f, t, d)
      case IsDerivationK2(f, t, d)       => (f, t, d)
      case _                             => abort(s"$obj seems not extending InstanceDef traits")
    }

    (name, from, to, drop)

  }

  class IsInstanceDef(t: Type, drop: Int) {
    val constrSymbol = t.typeConstructor.typeSymbol
    def unapply(objType: Type): Option[(Type, Type, Int)] =
      objType.baseType(constrSymbol) match {
        case TypeRef(_, _, List(tc))       => Some((tc, tc, drop))
        case TypeRef(_, _, List(from, to)) => Some((from, to, drop))
        case _                             => None
      }
  }
  def isInstanceDef[T: TypeTag](dropTParams: Int = 0) = new IsInstanceDef(typeOf[T], dropTParams)

  private def debug(s: Any)    = c.info(c.enclosingPosition, s.toString, false)
  private def abort(s: String) = c.abort(c.enclosingPosition, s)
}
