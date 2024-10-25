package com.twitter.finagle.context

import com.twitter.io.Buf
import com.twitter.util.Return
import com.twitter.util.Throw

class MarshalledContextTest extends AbstractContextTest {
  val ctx = new MarshalledContext

  val a = new ctx.Key[String]("a.key") {
    def marshal(value: String) = Buf.Utf8(value)
    def tryUnmarshal(buf: Buf) = buf match {
      case Buf.Utf8(value) => Return(value)
    }
  }

  val b = new ctx.Key[Int]("b.key") {
    def marshal(value: Int) = Buf.U32BE(value)
    def tryUnmarshal(buf: Buf) = buf match {
      case Buf.U32BE(value, Buf.Empty) => Return(value)
      case _ => Throw(new IllegalArgumentException)
    }
  }

  test("Key#marshalId is used when marshalling") {
    val key = new ctx.Key[String]("C.kEy") {
      def marshal(value: String) = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf) = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }

    ctx.let(key, "bar") {
      assert(
        ctx.marshal() == Map(
          key.marshalId -> Buf.Utf8("bar")
        )
      )
    }
  }

  test("keys unmarshal a into a case insensitive format") {
    val ctx = new MarshalledContext
    val lowerKey = new ctx.Key[String]("foo") {
      def marshal(value: String) = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf) = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }
    val upperKey = new ctx.Key[String]("FOO") {
      def marshal(value: String) = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf) = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }

    val data = ctx.let(upperKey, "bar") { ctx.marshal() }
    ctx.letUnmarshal(data) {
      val value = ctx.get(lowerKey)
      assert(value.isDefined)
      assert(value.get == "bar")
    }
  }

  test("retainIds") {
    val ctx = new MarshalledContext

    def stringKey(id: String): ctx.Key[String] = new ctx.Key[String](id) {
      def marshal(value: String): Buf = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf): Return[String] = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }

    val fooKey = stringKey("foo")
    val barKey = stringKey("bar")
    val bazKey = stringKey("baz")

    ctx.let(
      Seq(
        ctx.KeyValuePair(fooKey, "foo-value"),
        ctx.KeyValuePair(barKey, "bar-value"),
        ctx.KeyValuePair(bazKey, "baz-value"))) {

      assert(
        ctx.marshal() == Map(
          fooKey.marshalId -> Buf.Utf8("foo-value"),
          barKey.marshalId -> Buf.Utf8("bar-value"),
          bazKey.marshalId -> Buf.Utf8("baz-value"),
        ))

      ctx.retainIds(Set("foo", "baz")) {
        assert(
          ctx.marshal() == Map(
            fooKey.marshalId -> Buf.Utf8("foo-value"),
            bazKey.marshalId -> Buf.Utf8("baz-value")
          ))

        ctx.retainIds(Set("foo")) {
          assert(
            ctx.marshal() == Map(
              fooKey.marshalId -> Buf.Utf8("foo-value")
            ))

          // qux doesn't exist
          ctx.retainIds(Set("qux")) {
            assert(ctx.marshal().isEmpty)
          }
        }
      }
    }
  }

  test("key lookups are case insensitive") {
    val ctx = new MarshalledContext
    val lowerKey = new ctx.Key[String]("foo") {
      def marshal(value: String) = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf) = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }
    val upperKey = new ctx.Key[String]("FOO") {
      def marshal(value: String) = Buf.Utf8(value)
      def tryUnmarshal(buf: Buf) = buf match {
        case Buf.Utf8(value) => Return(value)
      }
    }

    ctx.let(lowerKey, "hello") {
      val value = ctx.get(upperKey)
      assert(value.isDefined)
      assert(value.get == "hello")

      val originalKeyValue = ctx.get(lowerKey)
      assert(originalKeyValue.isDefined)
      assert(originalKeyValue.get == "hello")
    }
  }

  test("Translucency: pass through, replace") {
    ctx.let(b, 333) {
      ctx.letUnmarshal(Seq(Buf.Utf8("bleep") -> Buf.Utf8("bloop"))) {
        assert(ctx.contains(b))
        assert(
          ctx.marshal().toMap == Map(
            Buf.Utf8("b.key") -> Buf.U32BE(333),
            Buf.Utf8("bleep") -> Buf.Utf8("bloop")
          )
        )
      }

      ctx.letUnmarshal(Seq(Buf.Utf8("bleep") -> Buf.Utf8("NOPE"))) {
        assert(
          ctx.marshal().toMap == Map(
            Buf.Utf8("b.key") -> Buf.U32BE(333),
            Buf.Utf8("bleep") -> Buf.Utf8("NOPE")
          )
        )
      }
    }
  }

  test("Only marshal the most recent binding for a given key") {

    ctx.letUnmarshal(Seq(a.marshalId -> Buf.Utf8("bloop"))) {
      assert(ctx.get(a) == Some("bloop"))
      assert(ctx.marshal().toMap == Map(a.marshalId -> Buf.Utf8("bloop")))

      ctx.letUnmarshal(Seq(a.marshalId -> Buf.Utf8("ok"))) {
        assert(ctx.get(a) == Some("ok"))
        assert(ctx.marshal().toMap == Map(a.marshalId -> Buf.Utf8("ok")))
      }

      ctx.let(a, "ok") {
        assert(ctx.get(a) == Some("ok"))
        assert(ctx.marshal().toMap == Map(a.marshalId -> Buf.Utf8("ok")))
      }
    }
  }

  test("Translucency: convert ok") {
    val number = 30301952
    ctx.letUnmarshal(Seq(Buf.Utf8("b.key") -> Buf.U32BE(number))) {
      assert(ctx.contains(b))
      assert(ctx.get(b) == Some(number))

      assert(ctx.marshal().toMap == Map(Buf.Utf8("b.key") -> Buf.U32BE(30301952)))
    }
  }

  test("Translucency: convert fail") {
    val number = 30301952
    // This fails because the buffer will be 8 bytes and
    // the unmarshal logic requires strictly 4 bytes.
    ctx.letUnmarshal(Seq(Buf.Utf8("b.key") -> Buf.U64BE(number))) {
      assert(!ctx.contains(b))
      assert(ctx.marshal().toMap == Map(Buf.Utf8("b.key") -> Buf.U64BE(number)))
    }
  }

  test("Unmarshal") {
    ctx.let(
      Seq(ctx.KeyValuePair(a, "ok"), ctx.KeyValuePair(b, 123), ctx.KeyValuePair(a, "notok"))) {
      val roundTrip = ctx.doUnmarshal(Map.empty, ctx.marshal())

      def checkKey(key: ctx.Key[_]): Unit = {
        roundTrip(key.lookupId) match {
          case t: ctx.Translucent => assert(t.unmarshal(key) == ctx.get(key))
          case other => fail(s"Unexpected structure: $other")
        }
      }

      checkKey(a)
      checkKey(b)

      val marshallRoundtrip = ctx.marshal(roundTrip)
      val marshallDirect = ctx.marshal()

      assert(marshallRoundtrip.iterator.sameElements(marshallDirect.iterator))
    }
  }

  test("Hashing failed extractions") {
    val bytes = Buf.ByteArray(0x01.toByte, 0x02.toByte, 0x03.toByte)
    // This is the result of hashing the above with SHA-256. If the algorithm changes
    // so too will the hash produced.
    assert(
      ctx.hashValue(bytes) == "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81")
  }
}
