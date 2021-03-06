///
/// Option Lints.
///
namespace Option {

    ///
    /// Simplifications.
    ///
    @theorem
    def countNone(): Bool =
        forall [a] (f: a -> Bool).
            Option.count(f, None) == 0

    @theorem
    def countSome(): Bool =
        forall [a] (f: a -> Bool, x: a).
            Option.count(f, Some(x)) == if (f(x)) 1 else 0

    @theorem
    def existsNone(): Bool =
        forall [a] (f: a -> Bool).
            Option.exists(f, None) == false

    @theorem
    def existsSome(): Bool =
        forall [a] (f: a -> Bool, x: a).
            Option.exists(f, Some(x)) == f(x)

    @theorem
    def filterNone(): Bool =
        forall [a] (f: a -> Bool).
            Option.filter(f, None) == None

    @theorem
    def findNone(): Bool =
        forall [a] (f: a -> Bool).
            Option.find(f, None) == None

    @theorem
    def flatMapNone(): Bool =
        forall [a, b] (f: a -> Option[b]).
            Option.flatMap(f, None) == None

    @theorem
    def flatMap2None1(): Bool =
        forall [a, b, c] (f: (a, b) -> Option[c], x: Option[b]).
            Option.flatMap2(f, x, None) == None

    @theorem
    def flatMap2None2(): Bool =
        forall [a, b, c] (f: (a, b) -> Option[c], y: Option[b]).
            Option.flatMap2(f, None, y) == None

    @theorem
    def flattenNone(): Bool =
        Option.flatten(None) == None

    @theorem
    def flattenSome(): Bool =
        forall [a] (o: Option[a]).
            Option.flatten(Some(o)) == o

    @theorem
    def foldLeftNone(): Bool =
        forall [a, b] (f: (b, a) -> b, x: b).
            Option.foldLeft(f, x, None) == x

    @theorem
    def foldRightNone(): Bool =
        forall [a, b] (f: (a, b) -> b, x: b).
            Option.foldRight(f, x, None) == x

    @theorem
    def forallNone(): Bool =
        forall [a] (f: a -> Bool).
            Option.forall(f, None) == true

    @theorem
    def getWithDefaultNone(): Bool =
        forall [a] (x: a).
            Option.getWithDefault(None, x) == x

    @theorem
    def isEmptyNone(): Bool =
        Option.isEmpty(None) == true

    @theorem
    def isEmptySome(): Bool =
        forall [a] (x: a).
            Option.isEmpty(Some(x)) == false

    @theorem
    def mapNone(): Bool =
        forall [a, b] (f: a -> b).
            Option.map(f, None) == None

    @theorem
    def map2None1(): Bool =
        forall [a, b] (f: (a, b) -> c, x: Option[a]).
            Option.map2(f, x, None) == None

    @theorem
    def map2None2(): Bool =
        forall [a, b] (f: (a, b) -> c, y: Option[a]).
            Option.map2(f, None, y) == None

    @theorem
    def replaceNone(): Bool =
        forall [a] (x: a, y: a).
            Option.replace(x, y, None) == None

    @theorem
    def unzipNone(): Bool =
        Option.unzip(None) == (None, None)

    @theorem
    def withDefaultNone1(): Bool =
        forall [a] (x: Option[a]).
            Option.withDefault(x, None) == x

    @theorem
    def withDefaultNone2(): Bool =
        forall [a] (y: Option[a]).
            Option.withDefault(None, y) == y

    @theorem
    def zipNone1(): Bool =
        forall [a] (x: a).
            Option.zip(x, None) == None

    @theorem
    def zipNone2(): Bool =
        forall [a] (y: a).
            Option.zip(None, y) == None

    @theorem // TODO: side-effects
    def filterFilter(): Bool =
        forall [a] (f: a -> Bool, g: a -> Bool, o: Option[a]).
            Option.filter(g, Option.filter(f, o)) == Option.filter(f >> g, o)

    @theorem // TODO: side-effects
    def findFind(): Bool =
        forall [a] (f: a -> Bool, g: a -> Bool, o: Option[a]).
            Option.find(g, Option.find(f, o)) == Option.find(f >> g, o)

    @theorem // TODO: side-effects
    def mapMap(): Bool =
        forall [a, b, c] (f: a -> b, g: b -> c, o: Option[a]).
            Option.map(g, Option.map(f, o)) == Option.map(f >> g, o)


    ///
    /// Use Cases.
    ///
    @theorem
    def useExists(): Bool =
        forall [a] (f: a -> Bool, o: Option[a]).
            Option.getWithDefault(Option.map(f, o), false) == Option.exists(f, o)

    @theorem
    def useForall(): Bool =
        forall [a] (f: a -> Bool, o: Option[a]).
            Option.getWithDefault(Option.map(f, o), true) == Option.forall(f, o)

    @theorem
    def useFilter(): Bool =
        forall [a] (f: a -> Bool, o: Option[a]).
            Option.flatMap(x -> if (f(x)) Some(x) else None, o) == Option.filter(f, o)

    @theorem
    def useWithDefault(): Bool =
        forall [a] (o1: Option[a], o2: Option[a]).
            (if (Option.isEmpty(o1)) o2 else o1) == Option.withDefault(o1, o2)

    @theorem
    def useReplace1(): Bool =
        forall [a] (x: a, y: a, o: Option[a]).
            (if (o == Some(x)) Some(y) else Some(x)) == Option.replace(x, y, o)

    @theorem
    def useReplace2(): Bool =
        forall [a] (x: a, y: a, o: Option[a]).
            (if (o == Some(x)) Some(y) else o) == Option.replace(x, y, o)

    @theorem
    def useReplace3(): Bool =
        forall [a] (x: a, y: a, o: Option[a]).
            Option.map(z -> if (z == x) x else y, o) == Option.replace(x, y, o)

    // TODO: Can we allow something like the below? How would we check it efficiently?
    @theorem
    def useReplace4(): Bool =
        forall [a] (x: a, y: a, o: Option[a]).
            (match o {
                case None => o
                case Some(z) => if (x == z) Some(y) else Some(z)
            }) == Option.replace(x, y, o)

    @theorem
    def useZip(): Bool =
        forall [a, b] (o1: Option[a], o2: Option[b]).
            Option.flatMap(x -> Option.map(y -> (x, y), o2), o1) == Option.zip(o1, o2)

    @theorem
    def useSequence(): Bool =
        forall [a] (xs: List[Option[a]]).
            Option.traverse(x -> x, xs) == Option.sequence(xs)

    @theorem
    def useTraverse(): Bool =
        forall [a, b] (f : a -> Option[b], xs: List[Option[a]]).
            Option.sequence(List.map(f, xs)) == Option.traverse(f, xs)

    ///
    /// Beliefs.
    ///
    @theorem
    def isEmptyCount(): Bool =
      forall [a] (o: Option[a], f: a -> Bool).
        Option.isEmpty(o) `implies` (Option.count(f, o) == 0)

    @theorem
    def isEmptyExists(): Bool =
      forall [a] (o: Option[a], f: a -> Bool).
        Option.isEmpty(o) `implies` (Option.exists(f, o) == false)

    @theorem
    def isEmptyFilter(): Bool =
      forall [a] (o: Option[a], f: a -> Bool).
        Option.isEmpty(o) `implies` (Option.filter(f, o) == None)

    @theorem
    def isEmptyFind(): Bool =
      forall [a] (o: Option[a], f: a -> Bool).
        Option.isEmpty(o) `implies` (Option.find(f, o) == None)

    @theorem
    def isEmptyFlatMap(): Bool =
      forall [a] (o: Option[a], f: a -> Option[a]).
        Option.isEmpty(o) `implies` (Option.flatMap(f, o) == None)

    @theorem
    def isEmptyFlatMap2a(): Bool =
      forall [a, b] (o1: Option[a], o2: Option[a], f: (a, b) -> Option[a]).
        Option.isEmpty(o1) `implies` (Option.flatMap2(f, o1, o2) == None)

    @theorem
    def isEmptyFlatMap2b(): Bool =
      forall [a, b] (o1: Option[a], o2: Option[a], f: (a, b) -> Option[a]).
        Option.isEmpty(o2) `implies` (Option.flatMap2(f, o1, o2) == None)

    @theorem
    def isEmptyFlatten(): Bool =
      forall [a] (o: Option[Option[a]]).
        Option.isEmpty(o) `implies` (Option.flatten(o) == None)

    @theorem
    def isEmptyFoldLeft(): Bool =
      forall [a, b] (o: Option[a], f: (b, a) -> b, i: b).
        Option.isEmpty(o) `implies` (Option.foldLeft(f, i, o) == i)

    @theorem
    def isEmptyFoldRight(): Bool =
      forall [a, b] (o: Option[a], f: (a, b) -> b, i: b).
        Option.isEmpty(o) `implies` (Option.foldLeft(f, o, i) == i)

    @theorem
    def isEmptyForall(): Bool =
      forall [a] (o: Option[a], f: a -> Bool).
        Option.isEmpty(o) `implies` (Option.forall(f, o) == true)

    @theorem
    def isEmptyGetWithDefault(): Bool =
      forall [a] (o: Option[a], d: a).
        Option.isEmpty(o) `implies` (Option.getWithDefault(o, d) == d)

    @theorem
    def isEmptyIsEmpty(): Bool =
      forall [a] (o: Option[a]).
        Option.isEmpty(o) `implies` (Option.isEmpty(o) == true)

    @theorem
    def isEmptyMap(): Bool =
      forall [a, b] (o: Option[a], f: a -> b).
        Option.isEmpty(o) `implies` (Option.map(f, o) == None)

    @theorem
    def isEmptyMap2a(): Bool =
      forall [a, b, c] (o1: Option[a], o2: Option[b], f: (a, b) -> c).
        Option.isEmpty(o1) `implies` (Option.map2(f, o1, o2) == None)

    @theorem
    def isEmptyMap2b(): Bool =
      forall [a, b, c] (o1: Option[a], o2: Option[b], f: (a, b) -> c).
        Option.isEmpty(o2) `implies` (Option.map2(f, o1, o2) == None)

    @theorem
    def isEmptyReplace(): Bool =
      forall [a, b] (o: Option[a], x: a, y: b).
        Option.isEmpty(o) `implies` (Option.replace(x, y, o) == None)

    @theorem
    def isEmptyUnzip(): Bool =
      forall [a, b] (o: Option[(a, b)]).
        Option.isEmpty(o) `implies` (Option.unzip(o) == (None, None))

    @theorem
    def isEmptyWithDefault(): Bool =
      forall [a] (o1: Option[a], o2: Option[b]).
        Option.isEmpty(o1) `implies` (Option.withDefault(o1, o2) == o2)

    @theorem
    def isEmptyZip1(): Bool =
      forall [a] (o1: Option[a], o2: Option[b]).
        Option.isEmpty(o1) `implies` (Option.zip(o1, o2) == None)

    @theorem
    def isEmptyZip2(): Bool =
      forall [a] (o1: Option[a], o2: Option[b]).
        Option.isEmpty(o2) `implies` (Option.zip(o1, o2) == None)

    @theorem
    def existsIsEmpty(): Bool =
      forall [a] (f: a -> Bool, o: Option[a]).
        Option.exists(f, o) `implies` (Option.isEmpty(o) == false)

    // TODO: Why do we have both find and filter?

}


///
/// Result Patterns.
///
namespace Result {

    ///
    /// Simplifications.
    ///

    ///
    /// Use Cases.
    ///

    ///
    /// Beliefs.
    ///

    // TODO: Remove isOk, isErr
    // TODO: Add sequence and traverse.

    @theorem
    def countOk(): Bool =
        forall [t, e] (f: t -> Bool, t: t).
            Result.count(f, Ok(t)) == if (f(t)) 1 else 0

    @theorem
    def countErr(): Bool =
        forall [t, e] (f: t -> Bool, e: e).
            Result.count(f, Err(e)) == 0

    @theorem
    def existsOk(): Bool =
        forall [t, e] (f: t -> Bool, t: t).
            Result.exists(f, Ok(t)) == f(t)

    @theorem
    def existsErr(): Bool =
        forall [t, e] (f: t -> Bool, e: e).
            Result.exists(f, Err(e)) == false

    @theorem
    def getWithDefaultMap(): Bool =
        forall [t, e] (f: t -> Bool, r: Result[t, e]).
            Result.getWithDefault(Result.map(f, r), false) == Result.exists(f, r)

}

// TODO: Add Validation data type?

///
/// List Patterns.
///
namespace List {

    @theorem
    def isEmptyCons(): Bool =
        forall [a] (x: a, xs: List[a]).
            List.isEmpty(x :: xs) == true

    @theorem
    def leftAppendNil(): Bool =
        forall [a] (xs: List[a]).
            List.append(xs, Nil) == xs

    @theorem
    def rightAppendNil(): Bool =
      forall [a] (xs: List[a]).
        List.append(Nil, xs) == xs

    @theorem
    def mapIdentity(): Bool =
        forall [a] (xs: List[a]).
            List.map(x -> x, xs) == xs

    @theorem
    def mapMap(): Bool =
      forall [a, b, c] (f: a -> b, g: b -> c, xs: List[a]).         // TODO: Need to account for effects.
        List.map(g, List.map(f, xs)) == List.map(f >> g, xs)

    @theorem
    def mapZip(): Bool =
      forall [a, b, c] (f: (a, b) -> c, xs: List[a], ys: List[b]).
        List.map(p -> f(fst(p), snd(p)), List.zip(xs, ys)) == List.map2(f, xs, ys)

    // TODO: Bug: Why does this not type check?
    //@theorem
    //def mapZip2(): Bool =
    //  forall [a, b, c] (f: (a, b) -> c, xs: List[a], ys: List[b]).
    //    List.map(f, List.zip(xs, ys)) == List.zipWith(f, xs, ys)

    @theorem
    def isEmptyFilter(): Bool =
      forall [a] (xs: List[a], f: a -> Bool).
        List.isEmpty(xs) `implies` (List.filter(f, xs) == Nil)          // TODO: What symbol to use here...

    @theorem
    def isEmptyExists(): Bool =
      forall [a] (xs: List[a], f: a -> Bool).
        List.isEmpty(xs) `implies` (List.exists(f, xs) == false)        // TODO: Use eq or not?

    @theorem
    def filterAlwaysTrue(): Bool =
      forall [a] (xs: List[a]).
        List.filter(_ -> true, xs) == xs

    @theorem
    def filterAlwaysFalse(): Bool =
      forall [a] (xs: List[a]).
        List.filter(_ -> false, xs) == Nil

    @theorem
    def memberOfIsEmpty(): Bool =
      forall [a] (x: a, xs: List[a]).
        List.memberOf(x, xs) `implies` (List.isEmpty(xs) == false)      // TODO: Use eq or not?

    @theorem
    def memberOfReplace(): Bool =
        forall [a] (x: a, y: a, xs: List[a]).
            !List.memberOf(x, xs) `implies` (List.replace(x, y, xs) == xs)      // TODO: What about negation here?

    @theorem
    def toSetFlatten(): Bool =
        forall [a] (xs: List[Set[a]]).
            Set.flatten(List.toSet(xs)) == List.foldLeft((x, y) -> Set.union(x, y), Set.empty(), xs)

    @theorem
    def foldConjFalse(): Bool =
        forall [a] (xs: List[a]).
            List.foldLeft((x, y) -> x && y, false, xs) == false

    @theorem
    def foldDisjTrue(): Bool =
        forall [a] (xs: List[a]).
            List.foldLeft((x, y) -> x || y, true, xs) == true

    @theorem
    def foldForall(): Bool =
        forall [a] (f: x -> Bool, xs: List[a]).
            List.foldLeft((x, y) -> x && y, true, List.map(f, xs)) == List.forall(f, xs)

    @theorem
    def foldExists(): Bool =
        forall [a] (f: x -> Bool, xs: List[a]).
            List.foldLeft((x, y) -> x || y, false, List.map(f, xs)) == List.exists(f, xs)

    @theorem
    def mapAndToSet(): Bool =
        forall [a, b] (f: a -> b, xs: List[a]).
            List.toSet(List.map(f, xs)) == List.foldLeft((x, y) -> Set.insert(f(y), x), Set.empty(), xs)

    @theorem
    def mapAndFlatten(): Bool =
        forall [a, b] (f: a -> List[b], xs: List[List[a]]).
            List.flatten(List.map(f, xs)) == List.flatMap(f, xs)

    // TODO: filterMap, findMap, map and fold.

    // TODO: fst(List.unzip(xs)) => // then typically both project should be used.
    // TODO: let x = List.unzip(xs); fst(x) // these should be the only usages of x, if it escapes then that is okay.

    // TODO: Do we want some first-class notion of "belief". Otherwise we have to write a million variants about when a list is empty then ...

// TODO: Compiler error.
//    @theorem
//    def useToMap(): Bool =
//        forall [a, b] (xs: List[(a, b)]).
//            List.foldLeft(match (acc, (k, v)) -> Map.insert(k, v, acc), Map.empty(), xs) == List.toMap(xs)

}


///
/// Set Patterns.
///
namespace Set {

    // TODO: contains, add

    @theorem
    def memberOfInsert(): Bool =
        forall [a] (s: Set[a], x: a).
            Set.memberOf(x, s) `implies` (Set.insert(x, s) == s)

}


///
/// Map Patterns.
///
namespace Map {

    ///
    /// Simplifications.
    ///
    @theorem
    def keysOfMemberOf(): Bool =
        forall [k] (k: k, m: Map[k, v]).
            Set.memberOf(k, Map.keysOf(m)) == Map.memberOf(k, m)

    ///
    /// Use Cases.
    ///

    @theorem
    def useGetWithDefault(): Bool =
        forall [k, v] (k: k, d: v, m: Map[k, v]).
            Option.getWithDefault(Map.get(k, m), d) == Map.getWithDefault(k, d, m)

    @theorem
    def useIsSubmapOf(): Bool =
        forall [k, v] (m1: Map[k, v], m2: Map[k, v]).
            Map.forall((k, v) -> Map.get(k, m1) == Some(v), m2) == Map.isSubmapOf(m1, m2)

    @theorem
    def useSingleton(): Bool =
        forall [k, v] (k: k, v: v).
            Map.insert(k, v, Map.empty()) == Map.singleton(k, v)

    @theorem
    def useValuesOf(): Bool =
        forall [k, v] (m: Map[k, v]).
            Map.foldLeft((acc, v) -> v :: acc, Nil, m) == Map.valuesOf(m) // TODO: foldLeft or foldRight?

    ///
    /// Beliefs.
    ///



    // TODO: size etc.
    // TODOL Union and fold.

    // TODO: How can we support pattern that detects a use of Map.filterWithKey where the key is unused?
    // TODO: @theorem
    // TODO: def filterWithKeyNoKey(): Bool =
    // TODO:    forall [k, v](f: (k, v) -> Bool, m: Map[k, v]).
    // TODO:        Map.filterWithKey((_, v) -> ???) -> Map.filter(???)

}


///
/// Arithmetic Patterns.
///

namespace Int8 {

    @theorem
    def leftAdditionByZero(): Bool =
        forall (x: Int8).
            0i8 + x == x

    @theorem
    def rightAdditionByZero(): Bool =
        forall (x: Int8).
            x + 0i8 == x

    @theorem
    def subtractionByZero(): Bool =
        forall (x: Int8).
            x - 0i8 == 0i8

    @theorem
    def subtractionBySelf(): Bool =
        forall (x: Int8).
            x - x == 0i8

    @theorem
    def leftMultiplicationByZero(): Bool =
        forall (x: Int8).
            0i8 * x == 0i8

    @theorem
    def rightMultiplicationByZero(): Bool =
        forall (x: Int8).
            x * 0i8 == 0i8

    @theorem
    def leftMultiplicationByOne(): Bool =
        forall (x: Int8).
            1i8 * x == x

    @theorem
    def rightMultiplicationByOne(): Bool =
        forall (x: Int8).
            x * 1i8 == x

    @theorem
    def divisionByOne(): Bool =
        forall (x: Int8).
            x / 1i8 == x

    @theorem
    def divisionBySelf(): Bool =
        forall (x: Int8). x / x == 1i8

}

namespace Int16 {

    @theorem
    def leftAdditionByZero(): Bool =
        forall (x: Int16).
            0i16 + x == x

    @theorem
    def rightAdditionByZero(): Bool =
        forall (x: Int16).
            x + 0i16 == x

    @theorem
    def subtractionByZero(): Bool =
        forall (x: Int16).
            x - 0i16 == 0i16

    @theorem
    def subtractionBySelf(): Bool =
        forall (x: Int16).
            x - x == 0i16

    @theorem
    def leftMultiplicationByZero(): Bool =
        forall (x: Int16).
            0i16 * x == 0i16

    @theorem
    def rightMultiplicationByZero(): Bool =
        forall (x: Int16).
            x * 0i16 == 0i16

    @theorem
    def leftMultiplicationByOne(): Bool =
        forall (x: Int16).
            1i16 * x == x

    @theorem
    def rightMultiplicationByOne(): Bool =
        forall (x: Int16).
            x * 1i16 == x

    @theorem
    def divisionByOne(): Bool =
        forall (x: Int16).
            x / 1i16 == x

    @theorem
    def divisionBySelf(): Bool =
        forall (x: Int16). x / x == 1i16

}

namespace Int32 {

    @theorem
    def leftAdditionByZero(): Bool =
        forall (x: Int32).
            0i32 + x == x

    @theorem
    def rightAdditionByZero(): Bool =
        forall (x: Int32).
            x + 0i32 == x

    @theorem
    def subtractionByZero(): Bool =
        forall (x: Int32).
            x - 0i32 == 0i32

    @theorem
    def subtractionBySelf(): Bool =
        forall (x: Int32).
            x - x == 0i32

    @theorem
    def leftMultiplicationByZero(): Bool =
        forall (x: Int32).
            0i32 * x == 0i32

    @theorem
    def rightMultiplicationByZero(): Bool =
        forall (x: Int32).
            x * 0i32 == 0i32

    @theorem
    def leftMultiplicationByOne(): Bool =
        forall (x: Int32).
            1i32 * x == x

    @theorem
    def rightMultiplicationByOne(): Bool =
        forall (x: Int32).
            x * 1i32 == x

    @theorem
    def divisionByOne(): Bool =
        forall (x: Int32).
            x / 1i32 == x

    @theorem
    def divisionBySelf(): Bool =
        forall (x: Int32). x / x == 1i32

}

namespace Int64 {

    @theorem
    def leftAdditionByZero(): Bool =
        forall (x: Int64).
            0i64 + x == x

    @theorem
    def rightAdditionByZero(): Bool =
        forall (x: Int64).
            x + 0i64 == x

    @theorem
    def subtractionByZero(): Bool =
        forall (x: Int64).
            x - 0i64 == 0i64

    @theorem
    def subtractionBySelf(): Bool =
        forall (x: Int64).
            x - x == 0i64

    @theorem
    def leftMultiplicationByZero(): Bool =
        forall (x: Int64).
            0i64 * x == 0i64

    @theorem
    def rightMultiplicationByZero(): Bool =
        forall (x: Int64).
            x * 0i64 == 0i64

    @theorem
    def leftMultiplicationByOne(): Bool =
        forall (x: Int64).
            1i64 * x == x

    @theorem
    def rightMultiplicationByOne(): Bool =
        forall (x: Int64).
            x * 1i64 == x

    @theorem
    def divisionByOne(): Bool =
        forall (x: Int64).
            x / 1i64 == x

    @theorem
    def divisionBySelf(): Bool =
        forall (x: Int64). x / x == 1i64

}

///
/// String Patterns.
///
namespace String {

    @theorem
    def leftConcatenateEmptyString(): Bool =
        forall (s: Str). "" + s == s

    @theorem
    def rightConcatenateEmptyString(): Bool =
        forall (s: Str). s + "" == s

}

///
/// Vector Patterns.
///
namespace Vec {

    @theorem
    def clearPop(): Bool =
        forall [a] (v: mut Vec[a]).
            Vec.clear(v) `followedBy` Vec.pop(v) == None

    @theorem
    def newPop(): Bool =
        Vec.pop(Vec.new()) == None // TODO: How to express?

    @theorem
    def pushClear(): Bool =
        forall [a] (v: mut Vec[a], x: a).
            Vec.push(v, x) `uselessLeft` Vec.clear(v) == ()

    @theorem
    def isEmptyMemberOf(): Bool =
        forall [a] (v: mut Vec[a], x: a).
            Vec.isEmpty(v) `implies` Vec.memberOf(x, v) == false

    @theorem
    def zeroLengthIsEmpty(): Bool =
        forall [a] (v: mut Vec[a]).
            (Vec.length(v) == 0) `implies` Vec.isEmpty(v) // TODO: What about its negation?

    // TODO: What about the dual?
    @theorem
    def popNoneIsEmpty(): Bool =
        forall [a] (v: mut Vec[a]).
            (Vec.pop(v) == None) `implies` Vec.isEmpty(v)

    @theorem
    def insertAtLength(): Bool =
        forall [a] (v: mut Vec[a], x: a).
            Vec.insert(v, x, Vec.length(v)) == Vec.push(v, x)

    @theorem
    def removeAtLength(): Bool =
        forall [a] (v: mut Vec[a]).
            Vec.remove(v, Vec.length(v)) `equiv` Vec.pop(v)

    @theorem
    def appendLeftEmpty(): Bool =
        forall [a] (v: mut Vec[a]).
            Vec.append(Vec.new(), v) == ??? // TODO: How to express that this is useless, when append does not return?

    @theorem
    def appendRightEmpty(): Bool =
        forall [a] (v: mut Vec[a]).
            Vec.append(v, Vec.new()) == ??? // TODO: How to express that this is useless, when append does not return?

    @theorem
    def newFollowedByReserve(): Bool =
        forall (n: Int32).
            Vec.reserve(Vec.new(), n) `equiv` Vec.newWithCapacity(n)

    // TODO: removeAll?
    //    @theorem
    //    def retainVecNew(): Bool =
    //        forall [a] (v: mut Vec[a]).
    //            Vec.retain(v, Vec.new()) == v

    // TODO: v.push(x) -> v.pop == Some(x)
    // TODO: replace, contains
    // TODO: truncate(zero)
    // TODO: new followed by reserve -> newWithCapacity
    // TODO: head == none -> !exists, etc.
    // TODO: last = none -> !exists, isEmpty, etc.
    // TODO: find == some -> nonEmpty
    // TODO: ditty for indexOf
    // TODO: push -> nonEmpty

    // TODO: Meta properties?

}

// TODO: How to define this extra linguistic predicate?
pub def followedBy(x: a, y: b): b = ???

// TODO: How to define this extra linguistic predicate?
pub def uselessLeft(x: a, y: b): b = ???

// TODO: How to define this extra linguistic predicate?
pub def uselessRight(x: a, y: b): b = ???

// TODO: How to define this extra linguistic predicate?
pub def equiv(x: a, y: b): Bool = ???

// TODO: How to define this extra linguistic predicate?
pub def implies(x: Bool, y: Bool): Bool = ???

    // TODO: Changing an if to an expression change semantics to be eager.


// TODO: min, max?

// TODO: List.flatMap(...).toSet -> replace by fold.

///
/// TODO: Open tasks
///

// TODO: How should we attach some documentation?
// TODO: What should the naming convention be?
// TODO: functions to add: List.sum, List.catMaybes , mapMaybe , traverse? sequence?
/// TODO: Introduce an annotation @unsafe or @unchecked to allow an expression that matches a code pattern.
/// TODO: It would be nice if we could use |> in patterns.
/// TODO: Do we want to normalize the AST somehow? For example if (!c) e1 else e2 could become if (c) e2 else e1?
// TODO: Add more arithmetic identities with shift and less commonly used operations.

///
/// TODO: Currently not possible to express:
///

/// x != 'a' || x != 'b' === false      // Problem: x is universally quantified, so are 'a' and 'b' but they must be distinct.
/// x == x or x != x                    // Problem: x is a variable, not a constant.
/// (x + 1) / (x + 1)                   // Problem: x is a variable, not a constant.
/// a[x] = 1; a[x] = 1                  // Problem: x is a variable and the array should not be accessed in between. Maybe too hard?
