// Copyright: 2010 - 2017 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import org.ensime.api.DeclaredAs
import scala.collection.immutable.Queue
import scala.collection.Set

import org.objectweb.asm.Opcodes._

sealed trait Access
case object Public extends Access
case object Default extends Access
case object Protected extends Access
case object Private extends Access

object Access {
  def apply(code: Int): Access =
    if ((ACC_PUBLIC & code) > 0) Public
    else if ((ACC_PROTECTED & code) > 0) Protected
    else if ((ACC_PRIVATE & code) > 0) Private
    else Default
}

sealed trait FullyQualifiedName {
  def contains(o: FullyQualifiedName): Boolean
  def fqnString: String
}

final case class PackageName(path: List[String]) extends FullyQualifiedName {
  def contains(o: FullyQualifiedName) = o match {
    case PackageName(pn) => pn.startsWith(path)
    case ClassName(p, _) => contains(p)
    case FieldName(c, _) => contains(c)
    case MethodName(c, _, _) => contains(c)
  }

  def fqnString = path.mkString(".")
  def parent = PackageName(path.init)
}

final case class ClassName(pack: PackageName, name: String)
    extends FullyQualifiedName with DescriptorType {

  def contains(o: FullyQualifiedName) = o match {
    case ClassName(op, on) if pack == op && on.startsWith(name) =>
      (on == name) || on.startsWith(name + "$")
    case FieldName(cn, _) => contains(cn)
    case MethodName(cn, _, _) => contains(cn)
    case _ => false
  }

  def fqnString =
    if (pack.path.isEmpty) name
    else pack.fqnString + "." + name

  def isPrimitive: Boolean = pack == ClassName.Root

  private def nonPrimitiveInternalString: String =
    "L" + (if (pack.path.isEmpty) name else pack.path.mkString("/") + "/" + name) + ";"

  lazy val internalString: String = {
    if (pack.path.isEmpty)
      name match {
        case "boolean" => "Z"
        case "byte" => "B"
        case "char" => "C"
        case "short" => "S"
        case "int" => "I"
        case "long" => "J"
        case "float" => "F"
        case "double" => "D"
        case "void" => "V"
        case _ => nonPrimitiveInternalString
      }
    else nonPrimitiveInternalString
  }
}

object ClassName {
  private val Root = PackageName(Nil)
  // we consider Primitives to be ClassNames
  private def Primitive(name: String): ClassName = ClassName(Root, name)

  val PrimitiveBoolean = Primitive("boolean")
  val PrimitiveByte = Primitive("byte")
  val PrimitiveChar = Primitive("char")
  val PrimitiveShort = Primitive("short")
  val PrimitiveInt = Primitive("int")
  val PrimitiveLong = Primitive("long")
  val PrimitiveFloat = Primitive("float")
  val PrimitiveDouble = Primitive("double")
  val PrimitiveVoid = Primitive("void")

  // must be a single type descriptor
  // strips array reification
  def fromDescriptor(desc: String): ClassName =
    DescriptorParser.parseType(desc) match {
      case c: ClassName => c
      case a: ArrayDescriptor => a.reifier
    }

  // internal name is effectively the FQN with / instead of dots
  def fromInternal(internal: String): ClassName = fromFqn(internal, '/')

  def fromFqn(internal: String, splitter: Char = '.'): ClassName = {
    val parts = internal.split(splitter)
    val (before, after) = parts.splitAt(parts.length - 1)
    ClassName(PackageName(before.toList), after(0))
  }

}

sealed trait MemberName extends FullyQualifiedName {
  def contains(o: FullyQualifiedName) = this == o
}

case class FieldName(
    owner: ClassName,
    name: String
// not always available in the ASM parser
//ret: DescriptorType
) extends MemberName {
  def fqnString = owner.fqnString + "." + name
}

// FQNs are not really unique, because method overloading, so fudge
// the descriptor into the FQN
final case class MethodName(
    owner: ClassName,
    name: String,
    descriptor: Descriptor
) extends MemberName {
  def fqnString = owner.fqnString + "." + name + descriptor.descriptorString
}

// Generics signature

sealed trait GenericSignature

sealed trait BoundType
case object UpperBound extends BoundType
case object LowerBound extends BoundType

final case class GenericClass(
  genericParam: Seq[GenericParam],
  superClasses: Seq[GenericClassName]
)

final case class GenericParam(
  name: String,
  classNames: Seq[GenericSignature]
)

final case class GenericClassName(
  className: ClassName,
  genericArg: Seq[GenericArg] = Seq.empty,
  innerClass: Seq[InnerClassName] = Seq.empty
) extends GenericSignature

final case class InnerClassName(
  name: String,
  genericArg: Seq[GenericArg] = Seq.empty
)

final case class GenericArg(
  boundType: Option[BoundType],
  genericSignature: GenericSignature
)

final case class GenericArray(className: GenericSignature)
  extends GenericSignature

final case class GenericVar(name: String)
  extends GenericSignature

// Descriptors

sealed trait DescriptorType {
  def internalString: String
}

final case class ArrayDescriptor(fqn: DescriptorType) extends DescriptorType {
  def reifier: ClassName = fqn match {
    case c: ClassName => c
    case a: ArrayDescriptor => a.reifier
  }
  def internalString = "[" + fqn.internalString
}
final case class Descriptor(params: List[DescriptorType], ret: DescriptorType) {
  def descriptorString =
    "(" + params.map(_.internalString).mkString("") + ")" + ret.internalString
}

sealed trait RawSymbol {
  def fqn: String
  def internalRefs: Set[FullyQualifiedName]
}

final case class RawClassfile(
    name: ClassName,
    generics: Option[GenericClass],
    innerClasses: Set[ClassName],
    superClass: Option[ClassName],
    interfaces: List[ClassName],
    access: Access,
    deprecated: Boolean,
    fields: List[RawField],
    methods: Queue[RawMethod],
    source: RawSource,
    isScala: Boolean,
    internalRefs: Set[FullyQualifiedName]
) extends RawSymbol {
  override def fqn: String = name.fqnString
}

final case class RawSource(
  filename: Option[String],
  line: Option[Int]
)

final case class RawField(
    name: FieldName,
    clazz: DescriptorType,
    generics: Option[String],
    access: Access,
    internalRefs: Set[FullyQualifiedName]
) extends RawSymbol {
  override def fqn: String = name.fqnString
}

final case class RawMethod(
    name: MethodName,
    access: Access,
    generics: Option[String],
    line: Option[Int],
    internalRefs: Set[FullyQualifiedName]
) extends RawSymbol {
  override def fqn: String = name.fqnString
}

sealed trait RawScalapSymbol {
  def declaredAs: DeclaredAs
  def access: Access
  def scalaName: String
  def typeSignature: String
}

final case class RawScalapClass(
  javaName: ClassName,
  scalaName: String,
  typeSignature: String,
  access: Access,
  declaredAs: DeclaredAs,
  fields: Map[String, RawScalapField],
  methods: Map[String, IndexedSeq[RawScalapMethod]],
  typeAliases: Map[String, RawType]
) extends RawScalapSymbol

final case class RawScalapField(
    javaName: FieldName,
    scalaName: String,
    typeSignature: String,
    access: Access
) extends RawScalapSymbol {
  override def declaredAs = DeclaredAs.Field
}

final case class RawScalapMethod(
    simpleName: String, //name of a method symbol, used to identify a group of overloaded methods (e.g. `foo`)
    scalaName: String, //full scala name of a method (e.g. `org.example.Foo#foo`)
    typeSignature: String,
    access: Access
) extends RawScalapSymbol {
  override def declaredAs = DeclaredAs.Method
}

final case class RawType(
    owner: ClassName,
    javaName: ClassName,
    scalaName: String,
    access: Access,
    typeSignature: String
) extends RawScalapSymbol {
  override def declaredAs = DeclaredAs.Field
}
