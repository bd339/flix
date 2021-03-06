/*
 *  Copyright 2017 Magnus Madsen
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package ca.uwaterloo.flix.language.phase

import ca.uwaterloo.flix.api.Flix
import ca.uwaterloo.flix.language.ast._
import ca.uwaterloo.flix.language.errors.ResolutionError
import ca.uwaterloo.flix.language.phase.Unification.Substitution
import ca.uwaterloo.flix.util.Validation._
import ca.uwaterloo.flix.util.{InternalCompilerException, Validation}

import scala.collection.mutable

/**
  * The Resolver phase performs name resolution on the program.
  */
object Resolver extends Phase[NamedAst.Root, ResolvedAst.Program] {

  /**
    * The maximum depth to which type aliases are unfolded.
    */
  val RecursionLimit: Int = 25

  /**
    * Performs name resolution on the given program `prog0`.
    */
  def run(prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Program, ResolutionError] = flix.phase("Resolver") {

    val definitionsVal = prog0.defs.flatMap {
      case (ns0, defs) => defs.map {
        case (_, defn) => resolve(defn, ns0, prog0) map {
          case d => d.sym -> d
        }
      }
    }

    val effsVal = prog0.effs.flatMap {
      case (ns0, effs) => effs.map {
        case (_, eff) => resolveEff(eff, ns0, prog0) map {
          case d => d.sym -> d
        }
      }
    }

    val handlersVal = prog0.handlers.flatMap {
      case (ns0, handlers) => handlers.map {
        case (_, handler) => resolveHandler(handler, ns0, prog0) map {
          case d => d.sym -> d
        }
      }
    }

    val classesVal = prog0.classes.flatMap {
      case (ns0, classes) => classes.map {
        case (_, clazz) => resolveClass(clazz, ns0, prog0) map {
          case c => c.head.sym -> c
        }
      }
    }

    val implsVal = prog0.impls.flatMap {
      case (ns0, impls) => impls.map {
        case impl => resolveImpl(impl, ns0, prog0) map {
          case c => c.head.sym -> c
        }
      }
    }

    val namedVal = prog0.named.map {
      case (sym, exp0) => Expressions.resolve(exp0, Map.empty, Name.RootNS, prog0).map {
        case exp =>
          // Introduce a synthetic definition for the expression.
          val doc = Ast.Doc(Nil, SourceLocation.Unknown)
          val ann = Ast.Annotations.Empty
          val mod = Ast.Modifiers(Ast.Modifier.Public :: Ast.Modifier.EntryPoint :: Nil)
          val tparams = Nil
          val fparam = ResolvedAst.FormalParam(Symbol.freshVarSym("_unit"), Ast.Modifiers.Empty, Type.Cst(TypeConstructor.Unit), SourceLocation.Unknown)
          val fparams = List(fparam)
          val sc = Scheme(Nil, Type.freshTypeVar())
          val eff = Eff.freshEffVar()
          val loc = SourceLocation.Unknown
          val defn = ResolvedAst.Def(doc, ann, mod, sym, tparams, fparams, exp, sc, eff, loc)
          sym -> defn
      }
    }

    val enumsVal = prog0.enums.flatMap {
      case (ns0, enums) => enums.map {
        case (_, enum) => resolve(enum, ns0, prog0) map {
          case d => d.sym -> d
        }
      }
    }

    val relationsVal = prog0.relations.flatMap {
      case (ns0, relations) => relations.map {
        case (_, relation) => resolveRelation(relation, ns0, prog0) map {
          case t => t.sym -> t
        }
      }
    }

    val latticesVal = prog0.lattices.flatMap {
      case (ns0, lattices) => lattices.map {
        case (_, lattice) => resolveLattice(lattice, ns0, prog0) map {
          case t => t.sym -> t
        }
      }
    }

    val latticeComponentsVal = prog0.latticeComponents.map {
      case (tpe0, lattice0) =>
        for {
          tpe <- lookupType(tpe0, lattice0.ns, prog0)
          lattice <- resolve(lattice0, lattice0.ns, prog0)
        } yield (tpe, lattice)
    }

    val propertiesVal = traverse(prog0.properties) {
      case (ns0, properties) => Properties.resolve(properties, ns0, prog0)
    }

    for {
      definitions <- sequence(definitionsVal)
      effs <- sequence(effsVal)
      handlers <- sequence(handlersVal)
      _ <- checkDefaultHandlers(effs, handlers)
      named <- sequence(namedVal)
      enums <- sequence(enumsVal)
      classes <- sequence(classesVal)
      impls <- sequence(implsVal)
      relations <- sequence(relationsVal)
      lattices <- sequence(latticesVal)
      latticeComponents <- sequence(latticeComponentsVal)
      properties <- propertiesVal
    } yield ResolvedAst.Program(
      definitions.toMap ++ named.toMap, effs.toMap, handlers.toMap, enums.toMap, classes.toMap, impls.toMap,
      relations.toMap, lattices.toMap, latticeComponents.toMap, properties.flatten, prog0.reachable, prog0.sources
    )
  }

  object Constraints {

    /**
      * Performs name resolution on the given `constraints` in the given namespace `ns0`.
      */
    def resolve(constraints: List[NamedAst.Constraint], tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[List[ResolvedAst.Constraint], ResolutionError] = {
      traverse(constraints)(c => resolve(c, tenv0, ns0, prog0))
    }

    /**
      * Performs name resolution on the given constraint `c0` in the given namespace `ns0`.
      */
    def resolve(c0: NamedAst.Constraint, tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Constraint, ResolutionError] = {
      for {
        ps <- traverse(c0.cparams)(p => Params.resolve(p, ns0, prog0))
        h <- Predicates.Head.resolve(c0.head, tenv0, ns0, prog0)
        bs <- traverse(c0.body)(b => Predicates.Body.resolve(b, tenv0, ns0, prog0))
      } yield ResolvedAst.Constraint(ps, h, bs, c0.loc)
    }

  }

  /**
    * Performs name resolution on the given definition `d0` in the given namespace `ns0`.
    */
  def resolve(d0: NamedAst.Def, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Def, ResolutionError] = d0 match {
    case NamedAst.Def(doc, ann, mod, sym, tparams0, fparams0, exp0, sc0, eff0, loc) =>
      val fparam = fparams0.head

      for {
        fparamType <- lookupType(fparam.tpe, ns0, prog0)
        fparams <- resolveFormalParams(fparams0, ns0, prog0)
        tparams <- resolveTypeParams(tparams0, ns0, prog0)
        exp <- Expressions.resolve(exp0, Map(fparam.sym -> fparamType), ns0, prog0)
        scheme <- resolveScheme(sc0, ns0, prog0)
      } yield ResolvedAst.Def(doc, ann, mod, sym, tparams, fparams, exp, scheme, eff0, loc)
  }

  /**
    * Performs name resolution on the given effect `eff0` in the given namespace `ns0`.
    */
  def resolveEff(eff0: NamedAst.Eff, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Eff, ResolutionError] = eff0 match {
    case NamedAst.Eff(doc, ann, mod, sym, tparams0, fparams0, sc0, eff0, loc) =>
      for {
        fparams <- resolveFormalParams(fparams0, ns0, prog0)
        tparams <- resolveTypeParams(tparams0, ns0, prog0)
        scheme <- resolveScheme(sc0, ns0, prog0)
      } yield ResolvedAst.Eff(doc, ann, mod, sym, tparams, fparams, scheme, eff0, loc)
  }

  /**
    * Performs name resolution on the given handler `handler0` in the given namespace `ns0`.
    */
  def resolveHandler(handler0: NamedAst.Handler, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Handler, ResolutionError] = handler0 match {
    case NamedAst.Handler(doc, ann, mod, ident, tparams0, fparams0, exp0, sc0, eff0, loc) =>
      // Compute the qualified name of the ident, since we need it to call lookupEff.
      val qname = Name.mkQName(ident)

      // TODO: Introduce appropriate type environment for handlers.
      val tenv0 = Map.empty[Symbol.VarSym, Type]

      for {
        eff <- lookupEff(qname, ns0, prog0)
        fparams <- resolveFormalParams(fparams0, ns0, prog0)
        tparams <- resolveTypeParams(tparams0, ns0, prog0)
        exp <- Expressions.resolve(exp0, tenv0, ns0, prog0)
        scheme <- resolveScheme(sc0, ns0, prog0)
      } yield ResolvedAst.Handler(doc, ann, mod, eff.sym, tparams, fparams, exp, scheme, eff0, loc)
  }

  /**
    * Performs name resolution on the given enum `e0` in the given namespace `ns0`.
    */
  def resolve(e0: NamedAst.Enum, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Enum, ResolutionError] = {
    val tparamsVal = traverse(e0.tparams)(p => Params.resolve(p, ns0, prog0))
    val casesVal = traverse(e0.cases) {
      case (name, NamedAst.Case(enum, tag, tpe)) =>
        for {
          t <- lookupType(tpe, ns0, prog0)
        } yield name -> ResolvedAst.Case(enum, tag, t)
    }
    for {
      tparams <- tparamsVal
      cases <- casesVal
      tpe <- lookupType(e0.tpe, ns0, prog0)
    } yield ResolvedAst.Enum(e0.doc, e0.mod, e0.sym, tparams, cases.toMap, tpe, e0.loc)
  }

  /**
    * Performs name resolution on the given class `clazz0` in the given namespace `ns0`.
    */
  def resolveClass(clazz0: NamedAst.Class, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.Class, ResolutionError] = clazz0 match {
    case NamedAst.Class(doc, mod, sym, quantifiers, head0, body0, sigs0, laws, loc) =>
      for {
        head <- resolveSimpleClass(head0, ns0, prog0)
        body <- traverse(body0)(resolveSimpleClass(_, ns0, prog0))
        sigs <- traverse(sigs0)(s => resolveSig(s._2, ns0, prog0))
      } yield {
        ResolvedAst.Class(doc, mod, sym, quantifiers, head, body, /* TODO */ Map.empty, /* TODO */ Nil, loc)
      }
  }

  /**
    * Performs name resolution on the given impl constraint `impl0` in the given namespace `ns0`.
    */
  def resolveImpl(impl0: NamedAst.Impl, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.Impl, ResolutionError] = impl0 match {
    case NamedAst.Impl(doc, mod, head0, body0, defs, loc) =>
      for {
        head <- resolveComplexClass(head0, ns0, prog0)
        body <- traverse(body0)(resolveComplexClass(_, ns0, prog0))
      } yield {
        ResolvedAst.Impl(doc, mod, head, body, /* TODO */ Nil, loc)
      }
  }

  /**
    * Performs name resolution on the given simple class atom `a` in the given namespace `ns0`.
    */
  def resolveSimpleClass(a: NamedAst.SimpleClass, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.SimpleClass, ResolutionError] = a match {
    case NamedAst.SimpleClass(qname, args, loc) =>
      for {
        sym <- lookupClass(qname, ns0, prog0)
      } yield {
        ResolvedAst.SimpleClass(sym, args, loc)
      }
  }

  /**
    * Performs name resolution on the given complex class atom `a` in the given namespace `ns0`.
    */
  def resolveComplexClass(a: NamedAst.ComplexClass, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.ComplexClass, ResolutionError] = a match {
    case NamedAst.ComplexClass(qname, polarity, args, loc) =>
      for {
        sym <- lookupClass(qname, ns0, prog0)
        ts <- traverse(args)(lookupType(_, ns0, prog0))
      } yield {
        ResolvedAst.ComplexClass(sym, polarity, ts, loc)
      }
  }

  /**
    * Performs name resolution on the given signature `sig0` in the given namespace `ns0`.
    */
  def resolveSig(sig0: NamedAst.Sig, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.Sig, ResolutionError] = {
    ResolvedAst.Sig().toSuccess
  }

  /**
    * Performs name resolution on the given lattice `l0` in the given namespace `ns0`.
    */
  def resolve(l0: NamedAst.LatticeComponents, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.LatticeComponents, ResolutionError] = {
    val tenv0 = Map.empty[Symbol.VarSym, Type]
    for {
      tpe <- lookupType(l0.tpe, ns0, prog0)
      bot <- Expressions.resolve(l0.bot, tenv0, ns0, prog0)
      top <- Expressions.resolve(l0.top, tenv0, ns0, prog0)
      equ <- Expressions.resolve(l0.equ, tenv0, ns0, prog0)
      leq <- Expressions.resolve(l0.leq, tenv0, ns0, prog0)
      lub <- Expressions.resolve(l0.lub, tenv0, ns0, prog0)
      glb <- Expressions.resolve(l0.glb, tenv0, ns0, prog0)
    } yield ResolvedAst.LatticeComponents(tpe, bot, top, equ, leq, lub, glb, ns0, l0.loc)
  }

  /**
    * Performs name resolution on the given relation `r0` in the given namespace `ns0`.
    */
  def resolveRelation(r0: NamedAst.Relation, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Relation, ResolutionError] = r0 match {
    case NamedAst.Relation(doc, mod, sym, tparams0, attr, loc) =>
      for {
        tparams <- resolveTypeParams(tparams0, ns0, prog0)
        attributes <- traverse(attr)(a => resolve(a, ns0, prog0))
      } yield {
        val ts = attributes.map(_.tpe)
        val base = Type.Cst(TypeConstructor.Relation(sym)): Type
        val init = Type.Cst(TypeConstructor.Tuple(ts.length)): Type
        val args = ts.foldLeft(init) {
          case (acc, t) => Type.Apply(acc, t)
        }
        val tpe = Type.Apply(base, args)

        val quantifiers = tparams.map(_.tpe)
        val scheme = Scheme(quantifiers, tpe)

        ResolvedAst.Relation(doc, mod, sym, tparams, attributes, scheme, loc)
      }
  }

  /**
    * Performs name resolution on the given table `t0` in the given namespace `ns0`.
    */
  def resolveLattice(l0: NamedAst.Lattice, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Lattice, ResolutionError] = l0 match {
    case NamedAst.Lattice(doc, mod, sym, tparams0, attr, loc) =>
      for {
        tparams <- resolveTypeParams(tparams0, ns0, prog0)
        attributes <- traverse(attr)(a => resolve(a, ns0, prog0))
      } yield {
        val ts = attributes.map(_.tpe)
        val base = Type.Cst(TypeConstructor.Lattice(sym)): Type
        val init = Type.Cst(TypeConstructor.Tuple(ts.length)): Type
        val args = ts.foldLeft(init) {
          case (acc, t) => Type.Apply(acc, t)
        }
        val tpe = Type.Apply(base, args)

        val quantifiers = tparams.map(_.tpe)
        val scheme = Scheme(quantifiers, tpe)

        ResolvedAst.Lattice(doc, mod, sym, tparams, attributes, scheme, loc)
      }
  }

  /**
    * Performs name resolution on the given attribute `a0` in the given namespace `ns0`.
    */
  private def resolve(a0: NamedAst.Attribute, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Attribute, ResolutionError] = {
    for {
      tpe <- lookupType(a0.tpe, ns0, prog0)
    } yield ResolvedAst.Attribute(a0.ident, tpe, a0.loc)
  }

  object Expressions {

    /**
      * Performs name resolution on the given expression `exp0` in the namespace `ns0`.
      */
    def resolve(exp0: NamedAst.Expression, tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Expression, ResolutionError] = {
      /**
        * Local visitor.
        */
      def visit(e0: NamedAst.Expression, tenv0: Map[Symbol.VarSym, Type]): Validation[ResolvedAst.Expression, ResolutionError] = e0 match {

        case NamedAst.Expression.Wild(tvar, evar, loc) =>
          ResolvedAst.Expression.Wild(tvar, evar, loc).toSuccess

        case NamedAst.Expression.Var(sym, evar, loc) => tenv0.get(sym) match {
          case None => ResolvedAst.Expression.Var(sym, sym.tvar, evar, loc).toSuccess
          case Some(tpe) =>
            // We always open schema types.
            ResolvedAst.Expression.Var(sym, Typer.openSchemaType(tpe), evar, loc).toSuccess
        }

        case NamedAst.Expression.Def(qname, tvar, evar, loc) =>
          lookupQName(qname, ns0, prog0) map {
            case LookupResult.Def(sym) => ResolvedAst.Expression.Def(sym, tvar, evar, loc)
            case LookupResult.Eff(sym) => ResolvedAst.Expression.Eff(sym, tvar, evar, loc)
            case LookupResult.Sig(sym) => ResolvedAst.Expression.Sig(sym, tvar, evar, loc)
          }

        case NamedAst.Expression.Hole(nameOpt, tpe, evar, loc) =>
          val sym = nameOpt match {
            case None => Symbol.freshHoleSym(loc)
            case Some(name) => Symbol.mkHoleSym(ns0, name)
          }
          ResolvedAst.Expression.Hole(sym, tpe, evar, loc).toSuccess

        case NamedAst.Expression.Unit(loc) => ResolvedAst.Expression.Unit(loc).toSuccess

        case NamedAst.Expression.True(loc) => ResolvedAst.Expression.True(loc).toSuccess

        case NamedAst.Expression.False(loc) => ResolvedAst.Expression.False(loc).toSuccess

        case NamedAst.Expression.Char(lit, loc) => ResolvedAst.Expression.Char(lit, loc).toSuccess

        case NamedAst.Expression.Float32(lit, loc) => ResolvedAst.Expression.Float32(lit, loc).toSuccess

        case NamedAst.Expression.Float64(lit, loc) => ResolvedAst.Expression.Float64(lit, loc).toSuccess

        case NamedAst.Expression.Int8(lit, loc) => ResolvedAst.Expression.Int8(lit, loc).toSuccess

        case NamedAst.Expression.Int16(lit, loc) => ResolvedAst.Expression.Int16(lit, loc).toSuccess

        case NamedAst.Expression.Int32(lit, loc) => ResolvedAst.Expression.Int32(lit, loc).toSuccess

        case NamedAst.Expression.Int64(lit, loc) => ResolvedAst.Expression.Int64(lit, loc).toSuccess

        case NamedAst.Expression.BigInt(lit, loc) => ResolvedAst.Expression.BigInt(lit, loc).toSuccess

        case NamedAst.Expression.Str(lit, loc) => ResolvedAst.Expression.Str(lit, loc).toSuccess

        case NamedAst.Expression.Apply(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.Apply(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.Lambda(fparam, exp, tvar, evar, loc) =>
          for {
            paramType <- lookupType(fparam.tpe, ns0, prog0)
            e <- visit(exp, tenv0 + (fparam.sym -> paramType))
            p <- Params.resolve(fparam, ns0, prog0)
          } yield ResolvedAst.Expression.Lambda(p, e, tvar, evar, loc)

        case NamedAst.Expression.Unary(op, exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.Unary(op, e, tvar, evar, loc)

        case NamedAst.Expression.Binary(op, exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.Binary(op, e1, e2, tvar, evar, loc)

        case NamedAst.Expression.IfThenElse(exp1, exp2, exp3, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
            e3 <- visit(exp3, tenv0)
          } yield ResolvedAst.Expression.IfThenElse(e1, e2, e3, tvar, evar, loc)

        case NamedAst.Expression.Stm(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.Stm(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.Let(sym, exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.Let(sym, e1, e2, tvar, evar, loc)

        case NamedAst.Expression.LetRec(sym, exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.LetRec(sym, e1, e2, tvar, evar, loc)

        case NamedAst.Expression.Match(exp, rules, tvar, evar, loc) =>
          val rulesVal = traverse(rules) {
            case NamedAst.MatchRule(pat, guard, body) =>
              for {
                p <- Patterns.resolve(pat, ns0, prog0)
                g <- visit(guard, tenv0)
                b <- visit(body, tenv0)
              } yield ResolvedAst.MatchRule(p, g, b)
          }

          for {
            e <- visit(exp, tenv0)
            rs <- rulesVal
          } yield ResolvedAst.Expression.Match(e, rs, tvar, evar, loc)

        case NamedAst.Expression.Switch(rules, tvar, evar, loc) =>
          val rulesVal = traverse(rules) {
            case (cond, body) => mapN(visit(cond, tenv0), visit(body, tenv0)) {
              case (c, b) => (c, b)
            }
          }
          rulesVal map {
            case rs => ResolvedAst.Expression.Switch(rs, tvar, evar, loc)
          }

        case NamedAst.Expression.Tag(enum, tag, expOpt, tvar, evar, loc) => expOpt match {
          case None =>
            // Case 1: The tag has does not have an expression.
            // Either it is implicitly Unit or the tag is used as a function.

            // Lookup the enum to determine the type of the tag.
            lookupEnumByTag(enum, tag, ns0, prog0) map {
              case decl =>
                // Retrieve the relevant case.
                val caze = decl.cases(tag.name)

                // Check if the tag value has Unit type.
                if (isUnitType(caze.tpe)) {
                  // Case 1.1: The tag value has Unit type. Construct the Unit expression.
                  val e = ResolvedAst.Expression.Unit(loc)
                  ResolvedAst.Expression.Tag(decl.sym, tag.name, e, tvar, evar, loc)
                } else {
                  // Case 1.2: The tag has a non-Unit type. Hence the tag is used as a function.
                  // If the tag is `Some` we construct the lambda: x -> Some(x).

                  // Construct a fresh symbol for the formal parameter.
                  val freshVar = Symbol.freshVarSym("x")

                  // Construct the formal parameter for the fresh symbol.
                  val freshParam = ResolvedAst.FormalParam(freshVar, Ast.Modifiers.Empty, Type.freshTypeVar(), loc)

                  // Construct a variable expression for the fresh symbol.
                  val varExp = ResolvedAst.Expression.Var(freshVar, freshVar.tvar, Eff.freshEffVar(), loc)

                  // Construct the tag expression on the fresh symbol expression.
                  val tagExp = ResolvedAst.Expression.Tag(decl.sym, caze.tag.name, varExp, Type.freshTypeVar(), evar, loc)

                  // Assemble the lambda expressions.
                  ResolvedAst.Expression.Lambda(freshParam, tagExp, Type.freshTypeVar(), evar, loc)
                }
            }
          case Some(exp) =>
            // Case 2: The tag has an expression. Perform resolution on it.
            for {
              d <- lookupEnumByTag(enum, tag, ns0, prog0)
              e <- visit(exp, tenv0)
            } yield ResolvedAst.Expression.Tag(d.sym, tag.name, e, tvar, evar, loc)
        }

        case NamedAst.Expression.Tuple(elms, tvar, evar, loc) =>
          for {
            es <- traverse(elms)(e => visit(e, tenv0))
          } yield ResolvedAst.Expression.Tuple(es, tvar, evar, loc)

        case NamedAst.Expression.RecordEmpty(tvar, evar, loc) =>
          ResolvedAst.Expression.RecordEmpty(tvar, evar, loc).toSuccess

        case NamedAst.Expression.RecordSelect(base, label, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
          } yield ResolvedAst.Expression.RecordSelect(b, label.name, tvar, evar, loc)

        case NamedAst.Expression.RecordExtend(label, value, rest, tvar, evar, loc) =>
          for {
            v <- visit(value, tenv0)
            r <- visit(rest, tenv0)
          } yield ResolvedAst.Expression.RecordExtend(label.name, v, r, tvar, evar, loc)

        case NamedAst.Expression.RecordRestrict(label, rest, tvar, evar, loc) =>
          for {
            r <- visit(rest, tenv0)
          } yield ResolvedAst.Expression.RecordRestrict(label.name, r, tvar, evar, loc)

        case NamedAst.Expression.ArrayLit(elms, tvar, evar, loc) =>
          for {
            es <- traverse(elms)(e => visit(e, tenv0))
          } yield ResolvedAst.Expression.ArrayLit(es, tvar, evar, loc)

        case NamedAst.Expression.ArrayNew(elm, len, tvar, evar, loc) =>
          for {
            e <- visit(elm, tenv0)
            ln <- visit(len, tenv0)
          } yield ResolvedAst.Expression.ArrayNew(e, ln, tvar, evar, loc)

        case NamedAst.Expression.ArrayLoad(base, index, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
            i <- visit(index, tenv0)
          } yield ResolvedAst.Expression.ArrayLoad(b, i, tvar, evar, loc)

        case NamedAst.Expression.ArrayStore(base, index, elm, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
            i <- visit(index, tenv0)
            e <- visit(elm, tenv0)
          } yield ResolvedAst.Expression.ArrayStore(b, i, e, tvar, evar, loc)

        case NamedAst.Expression.ArrayLength(base, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
          } yield ResolvedAst.Expression.ArrayLength(b, tvar, evar, loc)

        case NamedAst.Expression.ArraySlice(base, startIndex, endIndex, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
            i1 <- visit(startIndex, tenv0)
            i2 <- visit(endIndex, tenv0)
          } yield ResolvedAst.Expression.ArraySlice(b, i1, i2, tvar, evar, loc)

        case NamedAst.Expression.VectorLit(elms, tvar, evar, loc) =>
          for {
            es <- traverse(elms)(e => visit(e, tenv0))
          } yield ResolvedAst.Expression.VectorLit(es, tvar, evar, loc)

        case NamedAst.Expression.VectorNew(elm, len, tvar, evar, loc) =>
          for {
            e <- visit(elm, tenv0)
          } yield ResolvedAst.Expression.VectorNew(e, len, tvar, evar, loc)

        case NamedAst.Expression.VectorLoad(base, index, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
          } yield ResolvedAst.Expression.VectorLoad(b, index, tvar, evar, loc)

        case NamedAst.Expression.VectorStore(base, index, elm, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
            e <- visit(elm, tenv0)
          } yield ResolvedAst.Expression.VectorStore(b, index, e, tvar, evar, loc)

        case NamedAst.Expression.VectorLength(base, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
          } yield ResolvedAst.Expression.VectorLength(b, tvar, evar, loc)

        case NamedAst.Expression.VectorSlice(base, startIndex, optEndIndex, tvar, evar, loc) =>
          for {
            b <- visit(base, tenv0)
          } yield ResolvedAst.Expression.VectorSlice(b, startIndex, optEndIndex, tvar, evar, loc)

        case NamedAst.Expression.Ref(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.Ref(e, tvar, evar, loc)

        case NamedAst.Expression.Deref(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.Deref(e, tvar, evar, loc)

        case NamedAst.Expression.Assign(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.Assign(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.HandleWith(exp, bindings, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
            bs <- resolveHandlerBindings(bindings, tenv0, ns0, prog0)
          } yield ResolvedAst.Expression.HandleWith(e, bs, tvar, evar, loc)

        case NamedAst.Expression.Existential(fparam, exp, evar, loc) =>
          for {
            fp <- Params.resolve(fparam, ns0, prog0)
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.Existential(fp, e, evar, loc)

        case NamedAst.Expression.Universal(fparam, exp, evar, loc) =>
          for {
            fp <- Params.resolve(fparam, ns0, prog0)
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.Universal(fp, e, evar, loc)

        case NamedAst.Expression.Ascribe(exp, tpe, eff, loc) =>
          for {
            e <- visit(exp, tenv0)
            t <- lookupType(tpe, ns0, prog0)
          } yield ResolvedAst.Expression.Ascribe(e, t, eff, loc)

        case NamedAst.Expression.Cast(exp, tpe, eff, loc) =>
          for {
            e <- visit(exp, tenv0)
            t <- lookupType(tpe, ns0, prog0)
          } yield ResolvedAst.Expression.Cast(e, t, eff, loc)

        case NamedAst.Expression.TryCatch(exp, rules, tpe, evar, loc) =>
          val rulesVal = traverse(rules) {
            case NamedAst.CatchRule(sym, clazz, body) =>
              val exceptionType = Type.Cst(TypeConstructor.Native(clazz))
              visit(body, tenv0 + (sym -> exceptionType)) map {
                case b => ResolvedAst.CatchRule(sym, clazz, b)
              }
          }

          for {
            e <- visit(exp, tenv0)
            rs <- rulesVal
          } yield ResolvedAst.Expression.TryCatch(e, rs, tpe, evar, loc)

        case NamedAst.Expression.NativeConstructor(constructor, args, tpe, evar, loc) =>
          for {
            es <- traverse(args)(e => visit(e, tenv0))
          } yield ResolvedAst.Expression.NativeConstructor(constructor, es, tpe, evar, loc)

        case NamedAst.Expression.NativeField(field, tpe, evar, loc) =>
          ResolvedAst.Expression.NativeField(field, tpe, evar, loc).toSuccess

        case NamedAst.Expression.NativeMethod(method, args, tpe, evar, loc) =>
          for {
            es <- traverse(args)(e => visit(e, tenv0))
          } yield ResolvedAst.Expression.NativeMethod(method, es, tpe, evar, loc)

        case NamedAst.Expression.NewChannel(exp, tpe, evar, loc) =>
          for {
            t <- lookupType(tpe, ns0, prog0)
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.NewChannel(e, t, evar, loc)

        case NamedAst.Expression.GetChannel(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.GetChannel(e, tvar, evar, loc)

        case NamedAst.Expression.PutChannel(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.PutChannel(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.SelectChannel(rules, default, tvar, evar, loc) =>
          val rulesVal = traverse(rules) {
            case NamedAst.SelectChannelRule(sym, chan, body) =>
              for {
                c <- visit(chan, tenv0)
                b <- visit(body, tenv0)
              } yield ResolvedAst.SelectChannelRule(sym, c, b)
          }

          val defaultVal = default match {
            case Some(exp) =>
              for {
                e <- visit(exp, tenv0)
              } yield Some(e)
            case None => None.toSuccess
          }

          for {
            rs <- rulesVal
            d <- defaultVal
          } yield ResolvedAst.Expression.SelectChannel(rs, d, tvar, evar, loc)

        case NamedAst.Expression.ProcessSpawn(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.ProcessSpawn(e, tvar, evar, loc)

        case NamedAst.Expression.ProcessSleep(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.ProcessSleep(e, tvar, evar, loc)

        case NamedAst.Expression.ProcessPanic(msg, tvar, evar, loc) =>
          ResolvedAst.Expression.ProcessPanic(msg, tvar, evar, loc).toSuccess

        case NamedAst.Expression.FixpointConstraintSet(cs0, tvar, evar, loc) =>
          for {
            cs <- traverse(cs0)(Constraints.resolve(_, tenv0, ns0, prog0))
          } yield ResolvedAst.Expression.FixpointConstraintSet(cs, tvar, evar, loc)

        case NamedAst.Expression.FixpointCompose(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.FixpointCompose(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.FixpointSolve(exp, tvar, evar, loc) =>
          for {
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.FixpointSolve(e, tvar, evar, loc)

        case NamedAst.Expression.FixpointProject(qname, exp, tvar, evar, loc) =>
          for {
            sym <- lookupPredicateSymbol(qname, ns0, prog0)
            e <- visit(exp, tenv0)
          } yield ResolvedAst.Expression.FixpointProject(sym, e, tvar, evar, loc)

        case NamedAst.Expression.FixpointEntails(exp1, exp2, tvar, evar, loc) =>
          for {
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
          } yield ResolvedAst.Expression.FixpointEntails(e1, e2, tvar, evar, loc)

        case NamedAst.Expression.FixpointFold(qname, exp1, exp2, exp3, tvar, evar, loc) =>
          for {
            sym <- lookupPredicateSymbol(qname, ns0, prog0)
            e1 <- visit(exp1, tenv0)
            e2 <- visit(exp2, tenv0)
            e3 <- visit(exp3, tenv0)
          } yield ResolvedAst.Expression.FixpointFold(sym, e1, e2, e3, tvar, evar, loc)
      }

      visit(exp0, Map.empty)
    }

  }

  object Patterns {

    /**
      * Performs name resolution on the given pattern `pat0` in the namespace `ns0`.
      */
    def resolve(pat0: NamedAst.Pattern, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.Pattern, ResolutionError] = {

      def visit(p0: NamedAst.Pattern): Validation[ResolvedAst.Pattern, ResolutionError] = p0 match {
        case NamedAst.Pattern.Wild(tvar, loc) => ResolvedAst.Pattern.Wild(tvar, loc).toSuccess

        case NamedAst.Pattern.Var(sym, tvar, loc) => ResolvedAst.Pattern.Var(sym, tvar, loc).toSuccess

        case NamedAst.Pattern.Unit(loc) => ResolvedAst.Pattern.Unit(loc).toSuccess

        case NamedAst.Pattern.True(loc) => ResolvedAst.Pattern.True(loc).toSuccess

        case NamedAst.Pattern.False(loc) => ResolvedAst.Pattern.False(loc).toSuccess

        case NamedAst.Pattern.Char(lit, loc) => ResolvedAst.Pattern.Char(lit, loc).toSuccess

        case NamedAst.Pattern.Float32(lit, loc) => ResolvedAst.Pattern.Float32(lit, loc).toSuccess

        case NamedAst.Pattern.Float64(lit, loc) => ResolvedAst.Pattern.Float64(lit, loc).toSuccess

        case NamedAst.Pattern.Int8(lit, loc) => ResolvedAst.Pattern.Int8(lit, loc).toSuccess

        case NamedAst.Pattern.Int16(lit, loc) => ResolvedAst.Pattern.Int16(lit, loc).toSuccess

        case NamedAst.Pattern.Int32(lit, loc) => ResolvedAst.Pattern.Int32(lit, loc).toSuccess

        case NamedAst.Pattern.Int64(lit, loc) => ResolvedAst.Pattern.Int64(lit, loc).toSuccess

        case NamedAst.Pattern.BigInt(lit, loc) => ResolvedAst.Pattern.BigInt(lit, loc).toSuccess

        case NamedAst.Pattern.Str(lit, loc) => ResolvedAst.Pattern.Str(lit, loc).toSuccess

        case NamedAst.Pattern.Tag(enum, tag, pat, tvar, loc) =>
          for {
            d <- lookupEnumByTag(enum, tag, ns0, prog0)
            p <- visit(pat)
          } yield ResolvedAst.Pattern.Tag(d.sym, tag.name, p, tvar, loc)

        case NamedAst.Pattern.Tuple(elms, tvar, loc) =>
          for {
            es <- traverse(elms)(visit)
          } yield ResolvedAst.Pattern.Tuple(es, tvar, loc)

        case NamedAst.Pattern.Array(elms, tvar, loc) =>
          for {
            es <- traverse(elms)(visit)
          } yield ResolvedAst.Pattern.Array(es, tvar, loc)

        case NamedAst.Pattern.ArrayTailSpread(elms, sym, tvar, loc) =>
          for {
            es <- traverse(elms)(visit)
          } yield ResolvedAst.Pattern.ArrayTailSpread(es, sym, tvar, loc)

        case NamedAst.Pattern.ArrayHeadSpread(sym, elms, tvar, loc) =>
          for {
            es <- traverse(elms)(visit)
          } yield ResolvedAst.Pattern.ArrayHeadSpread(sym, es, tvar, loc)
      }

      visit(pat0)
    }

  }

  object Predicates {

    object Head {
      /**
        * Performs name resolution on the given head predicate `h0` in the given namespace `ns0`.
        */
      def resolve(h0: NamedAst.Predicate.Head, tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Predicate.Head, ResolutionError] = h0 match {
        case NamedAst.Predicate.Head.Atom(qname, den, terms, tvar, loc) =>
          for {
            sym <- lookupPredicateSymbol(qname, ns0, prog0)
            ts <- traverse(terms)(t => Expressions.resolve(t, tenv0, ns0, prog0))
          } yield ResolvedAst.Predicate.Head.Atom(sym, den, ts, tvar, loc)

        case NamedAst.Predicate.Head.Union(exp, tvar, loc) =>
          for {
            e <- Expressions.resolve(exp, tenv0, ns0, prog0)
          } yield ResolvedAst.Predicate.Head.Union(e, tvar, loc)
      }
    }

    object Body {
      /**
        * Performs name resolution on the given body predicate `b0` in the given namespace `ns0`.
        */
      def resolve(b0: NamedAst.Predicate.Body, tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Predicate.Body, ResolutionError] = b0 match {
        case NamedAst.Predicate.Body.Atom(qname, den, polarity, terms, tvar, loc) =>
          for {
            sym <- lookupPredicateSymbol(qname, ns0, prog0)
            ts <- traverse(terms)(t => Patterns.resolve(t, ns0, prog0))
          } yield ResolvedAst.Predicate.Body.Atom(sym, den, polarity, ts, tvar, loc)

        case NamedAst.Predicate.Body.Guard(exp, loc) =>
          for {
            e <- Expressions.resolve(exp, tenv0, ns0, prog0)
          } yield ResolvedAst.Predicate.Body.Guard(e, loc)
      }
    }

  }

  object Properties {

    /**
      * Performs name resolution on each of the given `properties` in the given namespace `ns0`.
      */
    def resolve(properties: List[NamedAst.Property], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[List[ResolvedAst.Property], ResolutionError] = {
      traverse(properties)(p => resolve(p, ns0, prog0))
    }

    /**
      * Performs name resolution on the given property `p0` in the given namespace `ns0`.
      */
    def resolve(p0: NamedAst.Property, ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.Property, ResolutionError] = {
      for {
        e <- Expressions.resolve(p0.exp, Map.empty, ns0, prog0)
      } yield ResolvedAst.Property(p0.law, p0.defn, e, p0.loc)
    }

  }

  object Params {

    /**
      * Performs name resolution on the given constraint parameter `cparam0` in the given namespace `ns0`.
      */
    def resolve(cparam0: NamedAst.ConstraintParam, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.ConstraintParam, ResolutionError] = cparam0 match {
      case NamedAst.ConstraintParam.HeadParam(sym, tpe, loc) => ResolvedAst.ConstraintParam.HeadParam(sym, tpe, loc).toSuccess
      case NamedAst.ConstraintParam.RuleParam(sym, tpe, loc) => ResolvedAst.ConstraintParam.RuleParam(sym, tpe, loc).toSuccess
    }

    /**
      * Performs name resolution on the given formal parameter `fparam0` in the given namespace `ns0`.
      */
    def resolve(fparam0: NamedAst.FormalParam, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.FormalParam, ResolutionError] = {
      for {
        t <- lookupType(fparam0.tpe, ns0, prog0)
      } yield ResolvedAst.FormalParam(fparam0.sym, fparam0.mod, t, fparam0.loc)
    }

    /**
      * Performs name resolution on the given type parameter `tparam0` in the given namespace `ns0`.
      */
    def resolve(tparam0: NamedAst.TypeParam, ns0: Name.NName, prog0: NamedAst.Root): Validation[ResolvedAst.TypeParam, ResolutionError] = {
      ResolvedAst.TypeParam(tparam0.name, tparam0.tpe, tparam0.loc).toSuccess
    }

  }

  /**
    * Performs name resolution on the given formal parameters `fparams0`.
    */
  def resolveFormalParams(fparams0: List[NamedAst.FormalParam], ns0: Name.NName, prog0: NamedAst.Root): Validation[List[ResolvedAst.FormalParam], ResolutionError] = {
    traverse(fparams0)(fparam => Params.resolve(fparam, ns0, prog0))
  }

  /**
    * Performs name resolution on the given type parameters `tparams0`.
    */
  def resolveTypeParams(tparams0: List[NamedAst.TypeParam], ns0: Name.NName, prog0: NamedAst.Root): Validation[List[ResolvedAst.TypeParam], ResolutionError] =
    traverse(tparams0)(tparam => Params.resolve(tparam, ns0, prog0))

  /**
    * Performs name resolution on the given handler bindings `bs0`.
    */
  def resolveHandlerBindings(bs0: List[NamedAst.HandlerBinding], tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[List[ResolvedAst.HandlerBinding], ResolutionError] = {
    // TODO: Check that there is no overlap?
    traverse(bs0)(b => resolveHandlerBindings(b, tenv0, ns0, prog0))
  }

  /**
    * Performs name resolution on the given handler binding `b0`.
    */
  def resolveHandlerBindings(b0: NamedAst.HandlerBinding, tenv0: Map[Symbol.VarSym, Type], ns0: Name.NName, prog0: NamedAst.Root)(implicit flix: Flix): Validation[ResolvedAst.HandlerBinding, ResolutionError] = b0 match {
    case NamedAst.HandlerBinding(qname, exp0) =>
      for {
        eff <- lookupEff(qname, ns0, prog0)
        exp <- Expressions.resolve(exp0, tenv0, ns0, prog0)
      } yield ResolvedAst.HandlerBinding(eff.sym, exp)
  }

  /**
    * Performs name resolution on the given scheme `sc0`.
    */
  def resolveScheme(sc0: NamedAst.Scheme, ns0: Name.NName, prog0: NamedAst.Root): Validation[Scheme, ResolutionError] = {
    for {
      base <- lookupType(sc0.base, ns0, prog0)
    } yield Scheme(sc0.quantifiers, base)
  }

  /**
    * The result of a qualified name lookup.
    */
  sealed trait LookupResult

  object LookupResult {

    case class Def(sym: Symbol.DefnSym) extends LookupResult

    case class Eff(sym: Symbol.EffSym) extends LookupResult

    case class Sig(sym: Symbol.SigSym) extends LookupResult

  }

  /**
    * Finds the definition with the qualified name `qname` in the namespace `ns0`.
    */
  def lookupQName(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Validation[LookupResult, ResolutionError] = {
    val defOpt = tryLookupDef(qname, ns0, prog0)
    val effOpt = tryLookupEff(qname, ns0, prog0)
    val sigOpt = tryLookupSig(qname, ns0, prog0)

    (defOpt, effOpt, sigOpt) match {
      case (None, None, None) => ResolutionError.UndefinedName(qname, ns0, qname.loc).toFailure
      case (Some(d), None, None) => getDefIfAccessible(d, ns0, qname.loc)
      case (None, Some(e), None) => getEffIfAccessible(e, ns0, qname.loc)
      case (None, None, Some(s)) => getSigIfAccessible(s, ns0, qname.loc)
      case (Some(d), Some(e), None) => ResolutionError.AmbiguousName(qname, ns0, List(d.loc, e.loc), qname.loc).toFailure
      case (Some(d), None, Some(s)) => ResolutionError.AmbiguousName(qname, ns0, List(d.loc, s.loc), qname.loc).toFailure
      case (None, Some(e), Some(s)) => ResolutionError.AmbiguousName(qname, ns0, List(e.loc, s.loc), qname.loc).toFailure
      case (Some(d), Some(e), Some(s)) => ResolutionError.AmbiguousName(qname, ns0, List(d.loc, e.loc, s.loc), qname.loc).toFailure
    }
  }

  /**
    * Tries to a def with the qualified name `qname` in the namespace `ns0`.
    */
  def tryLookupDef(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Option[NamedAst.Def] = {
    // Check whether the name is fully-qualified.
    if (qname.isUnqualified) {
      // Case 1: Unqualified name. Lookup in the current namespace.
      val defnOpt = prog0.defs.getOrElse(ns0, Map.empty).get(qname.ident.name)

      defnOpt match {
        case Some(defn) =>
          // Case 1.2: Found in the current namespace.
          Some(defn)
        case None =>
          // Case 1.1: Try the global namespace.
          prog0.defs.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name)
      }
    } else {
      // Case 2: Qualified. Lookup in the given namespace.
      prog0.defs.getOrElse(qname.namespace, Map.empty).get(qname.ident.name)
    }
  }

  /**
    * Tries to find an eff with the qualified name `qname` in the namespace `ns0`.
    */
  def lookupEff(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Validation[NamedAst.Eff, ResolutionError] = {
    tryLookupEff(qname, ns0, prog0) match {
      case None => ResolutionError.UndefinedEff(qname, ns0, qname.loc).toFailure
      case Some(eff) => eff.toSuccess
    }
  }

  /**
    * Finds the given effect with the qualified name `qname` in the namespace `ns0`.
    */
  def tryLookupEff(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Option[NamedAst.Eff] = {
    // Check whether the name is fully-qualified.
    if (qname.isUnqualified) {
      // Case 1: Unqualified name. Lookup in the current namespace.
      val defnOpt = prog0.effs.getOrElse(ns0, Map.empty).get(qname.ident.name)

      defnOpt match {
        case Some(eff) =>
          // Case 1.2: Found in the current namespace.
          Some(eff)
        case None =>
          // Case 1.1: Try the global namespace.
          prog0.effs.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name)
      }
    } else {
      // Case 2: Qualified. Lookup in the given namespace.
      prog0.effs.getOrElse(qname.namespace, Map.empty).get(qname.ident.name)
    }
  }

  /**
    * Finds the given signature with the qualified name `qname` in the namespace `ns0`.
    */
  def tryLookupSig(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Option[NamedAst.Sig] = {
    // Check whether the name is fully-qualified.
    if (qname.isUnqualified) {
      // TODO: We currently only lookup in the global namespace.

      // Case 1: Unqualified name. Lookup the classes in the global namespace.
      val classes = prog0.classes.getOrElse(Name.RootNS, Map.empty).values

      // A mutable collection of candidate signatures.
      val candidates = mutable.Set.empty[NamedAst.Sig]

      // Look through each class to see if it contains a usable signature.
      // TODO: This is very inefficient. It would be better to have a map of signatures in each ns.
      for (NamedAst.Class(doc, mod, sym, quantifiers, head, body, sigs, laws, loc) <- classes) {
        sigs.get(qname.ident.name) match {
          case None => // no such signature in the current class.
          case Some(sig) =>
            candidates += sig
        }
      }

      // Check how many candidate signatures were found.
      if (candidates.isEmpty) {
        // Case 1: No candidate signatures.
        None
      } else if (candidates.size == 1) {
        // Case 2: Exactly one candidate signature.
        Some(candidates.head)
      } else {
        // Case 3: Multiple candidate signatures.
        // TODO: Need to return validation?
        throw InternalCompilerException(s"Ambigious signature.")
      }
    } else {
      // Case 2: Qualified.
      // TODO: We currently only look for unqualified names.
      None
    }
  }

  /**
    * Finds the class with the qualified name `qname` in the namespace `ns0`.
    */
  def lookupClass(qname: Name.QName, ns0: Name.NName, prog0: NamedAst.Root): Validation[Symbol.ClassSym, ResolutionError] = {
    // Check whether the name is fully-qualified.
    if (qname.isUnqualified) {
      // Lookup in the current namespace.
      prog0.classes.getOrElse(ns0, Map.empty).get(qname.ident.name) match {
        case Some(clazz) =>
          // Case 1.1 : The class is defined in the current namespace.
          getClassIfAccessible(clazz, ns0, qname.loc).map(_.sym)
        case None =>
          // Case 1.2: The class was not found in the current namespace.
          // Try the root namespace.
          prog0.classes.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name) match {
            case Some(clazz) =>
              // Case 1.2.1: The class is defined in the root namespace.
              getClassIfAccessible(clazz, ns0, qname.loc).map(_.sym)
            case None =>
              // Case 1.2.2: The class was not found. Neither in the current namespace nor in the root namespace.
              ResolutionError.UndefinedClass(qname, qname.namespace, qname.loc).toFailure
          }
      }
    } else {
      // Lookup in the qualified namespace.
      prog0.classes.getOrElse(qname.namespace, Map.empty).get(qname.ident.name) match {
        case Some(clazz) =>
          // Case 2.1: The class was found in the qualified namespace.
          getClassIfAccessible(clazz, ns0, qname.loc).map(_.sym)
        case None =>
          // Case 2.2: The class was not found in the qualified namespace.
          ResolutionError.UndefinedClass(qname, qname.namespace, qname.loc).toFailure
      }
    }
  }

  /**
    * Finds the enum definition matching the given qualified name and tag.
    */
  def lookupEnumByTag(qname: Option[Name.QName], tag: Name.Ident, ns: Name.NName, prog0: NamedAst.Root): Validation[NamedAst.Enum, ResolutionError] = {
    /*
     * Lookup the tag name in all enums across all namespaces.
     */
    val globalMatches = mutable.Set.empty[NamedAst.Enum]
    for ((_, decls) <- prog0.enums) {
      for ((enumName, decl) <- decls) {
        for ((tagName, caze) <- decl.cases) {
          if (tag.name == tagName) {
            globalMatches += decl
          }
        }
      }
    }

    // Case 1: Exact match found. Simply return it.
    if (globalMatches.size == 1) {
      return getEnumIfAccessible(globalMatches.head, ns, tag.loc)
    }

    // Case 2: No or multiple matches found.
    // Lookup the tag in either the fully qualified namespace or the current namespace.
    val namespace = if (qname.exists(_.isQualified)) qname.get.namespace else ns

    /*
     * Lookup the tag name in all enums in the current namespace.
     */
    val namespaceMatches = mutable.Set.empty[NamedAst.Enum]
    for ((enumName, decl) <- prog0.enums.getOrElse(namespace, Map.empty[String, NamedAst.Enum])) {
      for ((tagName, caze) <- decl.cases) {
        if (tag.name == tagName) {
          namespaceMatches += decl
        }
      }
    }

    // Case 2.1: Exact match found in namespace. Simply return it.
    if (namespaceMatches.size == 1) {
      return getEnumIfAccessible(namespaceMatches.head, ns, tag.loc)
    }

    // Case 2.2: No matches found in namespace.
    if (namespaceMatches.isEmpty) {
      return ResolutionError.UndefinedTag(tag.name, ns, tag.loc).toFailure
    }

    // Case 2.3: Multiple matches found in namespace and no enum name.
    if (qname.isEmpty) {
      val locs = namespaceMatches.map(_.sym.loc).toList.sorted
      return ResolutionError.AmbiguousTag(tag.name, ns, locs, tag.loc).toFailure
    }

    // Case 2.4: Multiple matches found in namespace and an enum name is available.
    val filteredMatches = namespaceMatches.filter(_.sym.name == qname.get.ident.name)
    if (filteredMatches.size == 1) {
      return getEnumIfAccessible(filteredMatches.head, ns, tag.loc)
    }

    ResolutionError.UndefinedTag(tag.name, ns, tag.loc).toFailure
  }

  /**
    * Finds the table of the given `qname` in the namespace `ns`.
    */
  def lookupPredicateSymbol(qname: Name.QName, ns: Name.NName, prog0: NamedAst.Root): Validation[Symbol.PredSym, ResolutionError] = {
    // Lookup the relation and lattice.
    val relationOpt = lookupRelSymbol(qname, ns, prog0)
    val latticeOpt = lookupLatSymbol(qname, ns, prog0)

    (relationOpt, latticeOpt) match {
      case (None, None) => ResolutionError.UndefinedTable(qname, ns, qname.loc).toFailure
      case (Some(rel), None) => getRelationIfAccessible(rel, ns, qname.loc)
      case (None, Some(lat)) => getLatticeIfAccessible(lat, ns, qname.loc)
      case (Some(rel), Some(lat)) => ResolutionError.AmbiguousRelationOrLattice(qname, ns, List(rel.loc, lat.loc), qname.loc).toFailure
    }
  }

  /**
    * Finds the relation of the given `qname` in the namespace `ns`.
    */
  private def lookupRelSymbol(qname: Name.QName, ns: Name.NName, prog0: NamedAst.Root): Option[NamedAst.Relation] = {
    if (qname.isUnqualified) {
      // Lookup in the current namespace.
      prog0.relations.getOrElse(ns, Map.empty).get(qname.ident.name).orElse {
        // Lookup in the global namespace.
        prog0.relations.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name)
      }
    } else {
      // Lookup in the qualified namespace.
      prog0.relations.getOrElse(qname.namespace, Map.empty).get(qname.ident.name)
    }
  }

  /**
    * Finds the lattice of the given `qname` in the namespace `ns`.
    */
  private def lookupLatSymbol(qname: Name.QName, ns: Name.NName, prog0: NamedAst.Root): Option[NamedAst.Lattice] = {
    if (qname.isUnqualified) {
      // Lookup in the current namespace.
      prog0.lattices.getOrElse(ns, Map.empty).get(qname.ident.name).orElse {
        // Lookup in the global namespace.
        prog0.lattices.getOrElse(Name.RootNS, Map.empty).get(qname.ident.name)
      }
    } else {
      // Lookup in the qualified namespace.
      prog0.lattices.getOrElse(qname.namespace, Map.empty).get(qname.ident.name)
    }
  }


  // TODO: Move
  /**
    * Ensures that every declared effect in `effs` has one handler in `handlers`.
    */
  def checkDefaultHandlers(effs: List[(Symbol.EffSym, ResolvedAst.Eff)], handlers: List[(Symbol.EffSym, ResolvedAst.Handler)]): Validation[Unit, ResolutionError] = {
    //
    // Compute the declared and handled effects.
    //
    val declaredEffects = effs.map(_._1)
    val declaredHandlers = handlers.map(_._1)

    //
    // Check if there are any unhandled effects.
    //
    val unhandledEffects = declaredEffects.toSet -- declaredHandlers.toSet
    if (unhandledEffects.isEmpty)
      ().toSuccess
    else
      ResolutionError.UnhandledEffect(unhandledEffects.head).toFailure
  }

  /**
    * Returns `true` iff the given type `tpe0` is the Unit type.
    */
  def isUnitType(tpe: NamedAst.Type): Boolean = tpe match {
    case NamedAst.Type.Unit(loc) => true
    case _ => false
  }

  /**
    * Resolves the given type `tpe0` in the given namespace `ns0`.
    */
  def lookupType(tpe0: NamedAst.Type, ns0: Name.NName, root: NamedAst.Root)(implicit recursionDepth: Int = 0): Validation[Type, ResolutionError] = tpe0 match {
    case NamedAst.Type.Var(tvar, loc) => tvar.toSuccess
    case NamedAst.Type.Unit(loc) => Type.Cst(TypeConstructor.Unit).toSuccess
    case NamedAst.Type.Ambiguous(qname, loc) if qname.isUnqualified => qname.ident.name match {
      // Basic Types
      case "Unit" => Type.Cst(TypeConstructor.Unit).toSuccess
      case "Bool" => Type.Cst(TypeConstructor.Bool).toSuccess
      case "Char" => Type.Cst(TypeConstructor.Char).toSuccess
      case "Float" => Type.Cst(TypeConstructor.Float64).toSuccess
      case "Float32" => Type.Cst(TypeConstructor.Float32).toSuccess
      case "Float64" => Type.Cst(TypeConstructor.Float64).toSuccess
      case "Int" => Type.Cst(TypeConstructor.Int32).toSuccess
      case "Int8" => Type.Cst(TypeConstructor.Int8).toSuccess
      case "Int16" => Type.Cst(TypeConstructor.Int16).toSuccess
      case "Int32" => Type.Cst(TypeConstructor.Int32).toSuccess
      case "Int64" => Type.Cst(TypeConstructor.Int64).toSuccess
      case "BigInt" => Type.Cst(TypeConstructor.BigInt).toSuccess
      case "Str" => Type.Cst(TypeConstructor.Str).toSuccess
      case "Array" => Type.Cst(TypeConstructor.Array).toSuccess
      case "Channel" => Type.Cst(TypeConstructor.Channel).toSuccess
      case "Ref" => Type.Cst(TypeConstructor.Ref).toSuccess
      case "Vector" => Type.Cst(TypeConstructor.Vector).toSuccess

      // Disambiguate type.
      case typeName =>
        (lookupEnum(typeName, ns0, root), lookupRelation(typeName, ns0, root), lookupLattice(typeName, ns0, root), lookupTypeAlias(typeName, ns0, root)) match {
          // Case 1: Not Found.
          case (None, None, None, None) => ResolutionError.UndefinedType(qname, ns0, loc).toFailure

          // Case 2: Enum.
          case (Some(enum), None, None, None) => getEnumTypeIfAccessible(enum, ns0, ns0.loc)

          // Case 3: Relation.
          case (None, Some(rel), None, None) => getRelationTypeIfAccessible(rel, ns0, root, ns0.loc)

          // Case 4: Lattice.
          case (None, None, Some(lat), None) => getLatticeTypeIfAccessible(lat, ns0, root, ns0.loc)

          // Case 5: TypeAlias.
          case (None, None, None, Some(typealias)) => getTypeAliasIfAccessible(typealias, ns0, root, ns0.loc)

          // Case 6: Errors.
          case (x, y, z, w) =>
            val loc1 = x.map(_.loc)
            val loc2 = y.map(_.loc)
            val loc3 = z.map(_.loc)
            val loc4 = w.map(_.loc)
            val locs = List(loc1, loc2, loc3, loc4).flatten
            ResolutionError.AmbiguousType(typeName, ns0, locs, loc).toFailure
        }
    }
    case NamedAst.Type.Ambiguous(qname, loc) if qname.isQualified =>
      // Lookup the enum using the namespace.
      val decls = root.enums.getOrElse(qname.namespace, Map.empty)
      decls.get(qname.ident.name) match {
        case None => ResolutionError.UndefinedType(qname, ns0, loc).toFailure
        case Some(enum) => getEnumTypeIfAccessible(enum, ns0, ns0.loc)
      }
    case NamedAst.Type.Enum(sym) =>
      Type.Cst(TypeConstructor.Enum(sym, Kind.Star)).toSuccess

    case NamedAst.Type.Tuple(elms0, loc) =>
      for (
        elms <- traverse(elms0)(tpe => lookupType(tpe, ns0, root))
      ) yield Type.mkTuple(elms)

    case NamedAst.Type.RecordEmpty(loc) =>
      Type.RecordEmpty.toSuccess

    case NamedAst.Type.RecordExtend(label, value, rest, loc) =>
      for {
        v <- lookupType(value, ns0, root)
        r <- lookupType(rest, ns0, root)
      } yield Type.RecordExtend(label.name, v, r)

    case NamedAst.Type.SchemaEmpty(loc) =>
      Type.SchemaEmpty.toSuccess

    case NamedAst.Type.Schema(ts, rest, loc) =>
      // Translate the type into a schema row type.
      val init = lookupType(rest, ns0, root)

      // Fold over the predicate types and check that they are relations or lattices.
      Validation.foldRight(ts)(init) {
        case (predType, acc) =>
          // Lookup the type and check that is either a relation or lattice type.
          flatMapN(lookupType(predType, ns0, root)) {
            case t =>
              // Simplify the type. Required to get the appropriate type constructor.
              val s = simplify(t)

              // Determine the type of predicate symbol.
              s.typeConstructor match {
                case Type.Cst(TypeConstructor.Relation(sym)) => Type.SchemaExtend(sym, s, acc).toSuccess
                case Type.Cst(TypeConstructor.Lattice(sym)) => Type.SchemaExtend(sym, s, acc).toSuccess
                case nonRelationOrLatticeType => ResolutionError.NonRelationOrLattice(nonRelationOrLatticeType, loc).toFailure
              }
          }
      }

    case NamedAst.Type.Nat(len, loc) => Type.Succ(len, Type.Zero).toSuccess

    case NamedAst.Type.Native(fqn, loc) =>
      lookupJvmClass(fqn.mkString("."), loc) map {
        case clazz => Type.Cst(TypeConstructor.Native(clazz))
      }

    case NamedAst.Type.Arrow(tparams0, tresult0, loc) =>
      for (
        tparams <- traverse(tparams0)(tpe => lookupType(tpe, ns0, root));
        tresult <- lookupType(tresult0, ns0, root)
      ) yield Type.mkArrow(tparams, tresult)

    case NamedAst.Type.Apply(base0, targ0, loc) =>
      for (
        tpe1 <- lookupType(base0, ns0, root);
        tpe2 <- lookupType(targ0, ns0, root)
      ) yield simplify(Type.Apply(tpe1, tpe2))

  }

  /**
    * Optionally returns the enum with the given `name` in the given namespace `ns0`.
    */
  private def lookupEnum(typeName: String, ns0: Name.NName, root: NamedAst.Root): Option[NamedAst.Enum] = {
    val enumsInNamespace = root.enums.getOrElse(ns0, Map.empty)
    enumsInNamespace.get(typeName) orElse {
      val enumsInRootNS = root.enums.getOrElse(Name.RootNS, Map.empty)
      enumsInRootNS.get(typeName)
    }
  }

  /**
    * Optionally returns the relation with the given `name` in the given namespace `ns0`.
    */
  private def lookupRelation(typeName: String, ns0: Name.NName, root: NamedAst.Root): Option[NamedAst.Relation] = {
    val relationsInNS = root.relations.getOrElse(ns0, Map.empty)
    relationsInNS.get(typeName) orElse {
      val relationsInRootNS = root.relations.getOrElse(Name.RootNS, Map.empty)
      relationsInRootNS.get(typeName)
    }
  }

  /**
    * Optionally returns the lattice with the given `name` in the given namespace `ns0`.
    */
  private def lookupLattice(name: String, ns0: Name.NName, root: NamedAst.Root): Option[NamedAst.Lattice] = {
    val latticesInNS = root.lattices.getOrElse(ns0, Map.empty)
    latticesInNS.get(name) orElse {
      val latticesInRootNS = root.lattices.getOrElse(Name.RootNS, Map.empty)
      latticesInRootNS.get(name)
    }
  }

  /**
    * Optionally returns the type alias with the given `name` in the given namespace `ns0`.
    */
  private def lookupTypeAlias(typeName: String, ns0: Name.NName, root: NamedAst.Root): Option[NamedAst.TypeAlias] = {
    val typeAliasesInNamespace = root.typealiases.getOrElse(ns0, Map.empty)
    typeAliasesInNamespace.get(typeName) orElse {
      val typeAliasesInRootNS = root.typealiases.getOrElse(Name.RootNS, Map.empty)
      typeAliasesInRootNS.get(typeName)
    }
  }

  /**
    * Successfully returns the given class `clazz0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * A class `clazz0` is accessible from a namespace `ns0` if:
    *
    * (a) the class is marked public, or
    * (b) the class is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getClassIfAccessible(class0: NamedAst.Class, ns0: Name.NName, loc: SourceLocation): Validation[NamedAst.Class, ResolutionError] = {
    //
    // Check if the definition is marked public.
    //
    if (class0.mod.isPublic)
      return class0.toSuccess

    //
    // Check if the definition is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = class0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return class0.toSuccess

    //
    // The definition is not accessible.
    //
    ResolutionError.InaccessibleClass(class0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the given definition `defn0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * A definition `defn0` is accessible from a namespace `ns0` if:
    *
    * (a) the definition is marked public, or
    * (b) the definition is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getDefIfAccessible(defn0: NamedAst.Def, ns0: Name.NName, loc: SourceLocation): Validation[LookupResult, ResolutionError] = {
    //
    // Check if the definition is marked public.
    //
    if (defn0.mod.isPublic)
      return LookupResult.Def(defn0.sym).toSuccess

    //
    // Check if the definition is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = defn0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return LookupResult.Def(defn0.sym).toSuccess

    //
    // The definition is not accessible.
    //
    ResolutionError.InaccessibleDef(defn0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the given effect `eff0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * An effect `eff0` is accessible from a namespace `ns0` if:
    *
    * (a) the effect is marked public, or
    * (b) the effect is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getEffIfAccessible(eff0: NamedAst.Eff, ns0: Name.NName, loc: SourceLocation): Validation[LookupResult, ResolutionError] = {
    //
    // Check if the effect is marked public.
    //
    if (eff0.mod.isPublic)
      return LookupResult.Eff(eff0.sym).toSuccess

    //
    // Check if the effect is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = eff0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return LookupResult.Eff(eff0.sym).toSuccess

    //
    // The effect is not accessible.
    //
    ResolutionError.InaccessibleEff(eff0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the given signature `sig0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    */
  def getSigIfAccessible(sig0: NamedAst.Sig, ns0: Name.NName, loc: SourceLocation): Validation[LookupResult, ResolutionError] = {
    LookupResult.Sig(sig0.sym).toSuccess
  }

  /**
    * Successfully returns the given `enum0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * An enum is accessible from a namespace `ns0` if:
    *
    * (a) the definition is marked public, or
    * (b) the definition is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getEnumIfAccessible(enum0: NamedAst.Enum, ns0: Name.NName, loc: SourceLocation): Validation[NamedAst.Enum, ResolutionError] = {
    //
    // Check if the definition is marked public.
    //
    if (enum0.mod.isPublic)
      return enum0.toSuccess

    //
    // Check if the enum is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = enum0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return enum0.toSuccess

    //
    // The enum is not accessible.
    //
    ResolutionError.InaccessibleEnum(enum0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the given relation `rel0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * A relation `rel0` is accessible from a namespace `ns0` if:
    *
    * (a) the relation is marked public, or
    * (b) the relation is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getRelationIfAccessible(rel0: NamedAst.Relation, ns0: Name.NName, loc: SourceLocation): Validation[Symbol.RelSym, ResolutionError] = {
    //
    // Check if the relation is marked public.
    //
    if (rel0.mod.isPublic)
      return rel0.sym.toSuccess

    //
    // Check if the relation is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = rel0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return rel0.sym.toSuccess

    //
    // The relation is not accessible.
    //
    ResolutionError.InaccessibleRelation(rel0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the given lattice `lat0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    *
    * A relation `lat0` is accessible from a namespace `ns0` if:
    *
    * (a) the lattice is marked public, or
    * (b) the lattice is defined in the namespace `ns0` itself or in a parent of `ns0`.
    */
  def getLatticeIfAccessible(lat0: NamedAst.Lattice, ns0: Name.NName, loc: SourceLocation): Validation[Symbol.LatSym, ResolutionError] = {
    //
    // Check if the lattice is marked public.
    //
    if (lat0.mod.isPublic)
      return lat0.sym.toSuccess

    //
    // Check if the lattice is defined in `ns0` or in a parent of `ns0`.
    //
    val prefixNs = lat0.sym.namespace
    val targetNs = ns0.idents.map(_.name)
    if (targetNs.startsWith(prefixNs))
      return lat0.sym.toSuccess

    //
    // The lattice is not accessible.
    //
    ResolutionError.InaccessibleLattice(lat0.sym, ns0, loc).toFailure
  }

  /**
    * Successfully returns the type of the given `enum0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    */
  def getEnumTypeIfAccessible(enum0: NamedAst.Enum, ns0: Name.NName, loc: SourceLocation): Validation[Type, ResolutionError] =
    getEnumIfAccessible(enum0, ns0, loc) map {
      case enum => Type.Cst(TypeConstructor.Enum(enum.sym, Kind.Star))
    }

  /**
    * Successfully returns the type of the given `rel0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    */
  def getRelationTypeIfAccessible(rel0: NamedAst.Relation, ns0: Name.NName, root: NamedAst.Root, loc: SourceLocation): Validation[Type, ResolutionError] = {
    // NB: This is a small hack because the attribute types should be resolved according to the namespace of the relation.
    val declNS = getNS(rel0.sym.namespace)
    getRelationIfAccessible(rel0, ns0, loc) flatMap {
      case sym => traverse(rel0.attr)(a => lookupType(a.tpe, declNS, root)) map {
        case attr => mkRelationOrLattice(sym, rel0.tparams, attr)
      }
    }
  }

  /**
    * Successfully returns the type of the given `lat0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    */
  def getLatticeTypeIfAccessible(lat0: NamedAst.Lattice, ns0: Name.NName, root: NamedAst.Root, loc: SourceLocation): Validation[Type, ResolutionError] = {
    // NB: This is a small hack because the attribute types should be resolved according to the namespace of the relation.
    val declNS = getNS(lat0.sym.namespace)
    getLatticeIfAccessible(lat0, ns0, loc) flatMap {
      case sym => traverse(lat0.attr)(a => lookupType(a.tpe, declNS, root)) map {
        case attr => mkRelationOrLattice(sym, lat0.tparams, attr)
      }
    }
  }

  /**
    * Returns the type corresponding to the given predicate symbol `sym` with the given attribute types `ts`.
    */
  private def mkRelationOrLattice(predSym: Symbol.PredSym, tparams: List[NamedAst.TypeParam], ts: List[Type]): Type = predSym match {
    case sym: Symbol.RelSym =>
      val base = Type.Cst(TypeConstructor.Relation(sym)): Type
      val init = Type.Cst(TypeConstructor.Tuple(ts.length)): Type
      val args = ts.foldLeft(init) {
        case (acc, t) => Type.Apply(acc, t)
      }
      mkTypeLambda(tparams, Type.Apply(base, args))

    case sym: Symbol.LatSym =>
      val base = Type.Cst(TypeConstructor.Lattice(sym)): Type
      val init = Type.Cst(TypeConstructor.Tuple(ts.length)): Type
      val args = ts.foldLeft(init) {
        case (acc, t) => Type.Apply(acc, t)
      }
      mkTypeLambda(tparams, Type.Apply(base, args))
  }

  /**
    * Successfully returns the type of the given type alias `alia0` if it is accessible from the given namespace `ns0`.
    *
    * Otherwise fails with a resolution error.
    */
  private def getTypeAliasIfAccessible(alia0: NamedAst.TypeAlias, ns0: Name.NName, root: NamedAst.Root, loc: SourceLocation)(implicit recursionDepth: Int): Validation[Type, ResolutionError] = {
    // TODO: We should check if the type alias is accessible.

    ///
    /// Check whether we have hit the recursion limit while unfolding the type alias.
    ///
    if (recursionDepth == RecursionLimit) {
      return ResolutionError.RecursionLimit(alia0.sym, RecursionLimit, alia0.loc).toFailure
    }

    // Retrieve the declaring namespace.
    val declNS = getNS(alia0.sym.namespace)

    // Construct a type lambda for each type parameter.
    mapN(lookupType(alia0.tpe, declNS, root)(recursionDepth + 1)) {
      case base => mkTypeLambda(alia0.tparams, base)
    }
  }

  /**
    * Returns the given type `tpe` wrapped in a type lambda for the given type parameters `tparam`.
    */
  private def mkTypeLambda(tparams: List[NamedAst.TypeParam], tpe: Type): Type =
    tparams.foldRight(tpe) {
      case (tparam, acc) => Type.Lambda(tparam.tpe, acc)
    }

  /**
    * Returns a simplified (evaluated) form of the given type `tpe0`.
    *
    * Performs beta-reduction of type abstractions and applications.
    */
  private def simplify(tpe0: Type): Type = {
    def eval(t: Type, subst: Map[Type.Var, Type]): Type = t match {
      case tvar: Type.Var => subst.getOrElse(tvar, tvar)
      case Type.Cst(_) => t

      case Type.Arrow(_, _) => t

      case Type.RecordEmpty => t

      case Type.RecordExtend(label, value, rest) =>
        val t1 = eval(value, subst)
        val t2 = eval(rest, subst)
        Type.RecordExtend(label, t1, t2)

      case Type.SchemaEmpty => t

      case Type.SchemaExtend(sym, tpe, rest) =>
        val t1 = eval(tpe, subst)
        val t2 = eval(rest, subst)
        Type.SchemaExtend(sym, t1, t2)

      case Type.Zero => t

      case Type.Succ(len, t) => Type.Succ(len, eval(t, subst))

      case Type.Lambda(tvar, tpe) => Type.Lambda(tvar, eval(tpe, subst))

      // TODO: Does not take variable capture into account.
      case Type.Apply(tpe1, tpe2) => (eval(tpe1, subst), eval(tpe2, subst)) match {
        case (Type.Lambda(tvar, tpe3), t2) => eval(tpe3, subst + (tvar -> t2))
        case (t1, t2) => Type.Apply(t1, t2)
      }
    }

    eval(tpe0, Map.empty)
  }

  /**
    * Returns the class reflection object for the given `className`.
    */
  private def lookupJvmClass(className: String, loc: SourceLocation): Validation[Class[_], ResolutionError] = try {
    Class.forName(className).toSuccess
  } catch {
    case ex: ClassNotFoundException => ResolutionError.UndefinedNativeClass(className, loc).toFailure
  }

  /**
    * Returns a synthetic namespace obtained from the given sequence of namespace `parts`.
    */
  private def getNS(parts: List[String]): Name.NName = {
    val sp1 = SourcePosition.Unknown
    val sp2 = SourcePosition.Unknown
    val idents = parts.map(s => Name.Ident(sp1, s, sp2))
    Name.NName(sp1, idents, sp2)
  }

}
