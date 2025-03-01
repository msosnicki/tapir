# Schema derivation

Implicit schemas for basic types (`String`, `Int`, etc.), and their collections (`Option`, `List`, `Array` etc.) are
defined out-of-the box. They don't contain any meta-data, such as descriptions or example values.

For case classes, `Schema[_]` values can be derived automatically using
[Magnolia](https://github.com/softwaremill/magnolia), given that schemas are defined for all the case class's fields.

There are two policies of custom type derivation are available:

* automatic derivation
* semi automatic derivation

## Automatic derivation

Case classes, traits and their children are recursively derived by Magnolia.

Importing `sttp.tapir.generic.auto._` (or extending the `SchemaDerivation` trait) enables fully automatic derivation
for `Schema`:

```scala mdoc:silent:reset
import sttp.tapir.Schema
import sttp.tapir.generic.auto._

case class Parent(child: Child)
case class Child(value: String)

// implicit schema used by codecs
implicitly[Schema[Parent]]
```

If you have a case class which contains some non-standard types (other than strings, number, other case classes,
collections), you only need to provide implicit schemas for them. Using these, the rest will be derived automatically.

Note that when using [datatypes integrations](integrations.md), respective codecs must also be imported to enable the
derivation, e.g. for [newtype](integrations.md#newtype-integration) you'll have to add
`import sttp.tapir.codec.newtype._` or extend `TapirCodecNewType`.

## Semi-automatic derivation

Semi-automatic derivation can be done using `Schema.derived[T]`.

It only derives selected type `T`. However, derivation is not recursive: schemas must be explicitly defined for every
child type.

This mode is easier to debug and helps to avoid issues encountered by automatic mode (wrong schemas for value classes
or custom types):

```scala mdoc:silent:reset
import sttp.tapir.Schema

case class Parent(child: Child)
case class Child(value: String)

implicit lazy val sChild: Schema[Child] = Schema.derived
implicit lazy val sParent: Schema[Parent] = Schema.derived
```

Note that while schemas for regular types can be safely defined as `val`s, in case of recursive values, the schema
values must be `lazy val`s.

## Derivation for recursive types in Scala3

In Scala3, any schemas for recursive types need to be provided as typed `implicit def` (not a `given`)!
For example:

```scala mdoc:silent
case class RecursiveTest(data: List[RecursiveTest])
object RecursiveTest {
  implicit def f1Schema: Schema[RecursiveTest] = Schema.derived[RecursiveTest]
}
```

The implicit doesn't have to be defined in the companion object, just anywhere in scope. This applies to cases where
the schema is looked up implicitly, e.g. for `jsonBody`.

## Configuring derivation

It is possible to configure Magnolia's automatic derivation to use `snake_case`, `kebab-case` or a custom field naming
policy, by providing an implicit `sttp.tapir.generic.Configuration` value. This influences how the low-level
representation is described in documentation:

```scala mdoc:silent
import sttp.tapir.generic.Configuration

implicit val customConfiguration: Configuration =
  Configuration.default.withSnakeCaseMemberNames
```

## Manually providing schemas

Alternatively, `Schema[_]` values can be defined by hand, either for whole case classes, or only for some of its fields.
For example, here we state that the schema for `MyCustomType` is a `String`:

```scala mdoc:silent
import sttp.tapir._

case class MyCustomType()
implicit val schemaForMyCustomType: Schema[MyCustomType] = Schema.string
// or, if the low-level representation is e.g. a number
implicit val anotherSchemaForMyCustomType: Schema[MyCustomType] = Schema(SchemaType.SInteger())
```

## Sealed traits / coproducts

Schema derivation for coproduct types (sealed trait hierarchies) is supported as well. By default, such hierarchies
will be represented as a coproduct which contains a list of child schemas, without any discriminator field.

A discriminator field can be specified for coproducts by providing it in the configuration; this will be only used
during automatic and semi-automatic derivation:

```scala mdoc:silent:reset
import sttp.tapir.generic.Configuration

implicit val customConfiguration: Configuration =
  Configuration.default.withDiscriminator("who_am_i")
```

Alternatively, derived schemas can be customised (see below), and a discriminator can be added by calling
the `SchemaType.SCoproduct.addDiscriminatorField(name, schema, maping)` method.

Finally, if the discriminator is a field that's defined on the base trait (and hence in each implementation), the
schemas can be specified using `Schema.oneOfUsingField`, for example (this will also generate the appropriate
mappings):

```scala mdoc:silent:reset
sealed trait Entity {
  def kind: String
} 
case class Person(firstName:String, lastName:String) extends Entity { 
  def kind: String = "person"
}
case class Organization(name: String) extends Entity {
  def kind: String = "org"  
}

import sttp.tapir._

val sPerson = Schema.derived[Person]
val sOrganization = Schema.derived[Organization]
implicit val sEntity: Schema[Entity] = 
    Schema.oneOfUsingField[Entity, String](_.kind, _.toString)("person" -> sPerson, "org" -> sOrganization)
```

## Customising derived schemas

### Using annotations

In some cases, it might be desirable to customise the derived schemas, e.g. to add a description to a particular
field of a case class. One way the automatic & semi-automatic derivation can be customised is using annotations:

* `@encodedName` sets name for case class's field which is used in the encoded form (and also in documentation)
* `@description` sets description for the whole case class or its field
* `@default` sets default value for a case class field
* `@encodedExample` sets example value for a case class field which is used in the documentation in the encoded form
* `@format` sets the format for a case class field
* `@deprecated` marks a case class's field as deprecated
* `@validate` will add the given validator to a case class field

These annotations will adjust schemas, after they are looked up using the normal implicit mechanisms.

### Using implicits

If the target type isn't accessible or can't be modified, schemas can be customized by looking up an implicit instance
of the `Derived[Schema[T]]` type, modifying the value, and assigning it to an implicit schema.

When such an implicit `Schema[T]` is in scope will have higher priority than the built-in low-priority conversion
from `Derived[Schema[T]]` to `Schema[T]`.

Schemas for products/coproducts (case classes and case class families) can be traversed and modified using
`.modify` method. To traverse collections, use `.each`.

For example:

```scala mdoc:silent:reset
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.generic.Derived

case class Basket(fruits: List[FruitAmount])
case class FruitAmount(fruit: String, amount: Int)
implicit val customBasketSchema: Schema[Basket] = implicitly[Derived[Schema[Basket]]].value
  .modify(_.fruits.each.amount)(_.description("How many fruits?"))
```

There is also an unsafe variant of this method, but it should be avoided in most cases.
The "unsafe" prefix comes from the fact that the method takes a list of strings,
which represent fields, and the correctness of this specification is not checked.

Non-standard collections can be unwrapped in the modification path by providing an implicit value of `ModifyFunctor`.

### Using value classes/tagged types

An alternative to customising schemas for case class fields of primitive type (e.g. `Int`s), is creating a unique type.
As schema lookup is type-driven, if a schema for a such type is provided as an implicit value, it will be used 
during automatic or semi-automatic schema derivation. Such schemas can have custom meta-data, including description,
validation, etc.

To introduce unique types for primitive values, which don't have a runtime overhead, you can use value classes or 
[type tagging](https://github.com/softwaremill/scala-common#tagging).

For example, to support an integer wrapped in a value type in a json body, we need to provide Circe encoders and
decoders (if that's the json library that we are using), schema information with validator:

```scala mdoc:silent:reset-object
import sttp.tapir._
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import io.circe.{ Encoder, Decoder }
import io.circe.generic.semiauto._

case class Amount(v: Int) extends AnyVal
case class FruitAmount(fruit: String, amount: Amount)

implicit val amountSchema: Schema[Amount] = Schema(SchemaType.SInteger()).validate(Validator.min(1).contramap(_.v))
implicit val amountEncoder: Encoder[Amount] = Encoder.encodeInt.contramap(_.v)
implicit val amountDecoder: Decoder[Amount] = Decoder.decodeInt.map(Amount.apply)

implicit val decoder: Decoder[FruitAmount] = deriveDecoder[FruitAmount]
implicit val encoder: Encoder[FruitAmount] = deriveEncoder[FruitAmount]

val e: PublicEndpoint[FruitAmount, Unit, Unit, Nothing] =
  endpoint.in(jsonBody[FruitAmount])
```

## Next

Read on about [validation](validation.md).
