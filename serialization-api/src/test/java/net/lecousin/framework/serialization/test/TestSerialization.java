package net.lecousin.framework.serialization.test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Supplier;

import net.lecousin.framework.concurrent.async.AsyncSupplier;
import net.lecousin.framework.concurrent.async.IAsync;
import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.core.test.io.TestIOError;
import net.lecousin.framework.io.FileIO;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.log.Logger;
import net.lecousin.framework.log.LoggerFactory;
import net.lecousin.framework.math.IntegerUnit.Unit;
import net.lecousin.framework.serialization.CustomSerializer;
import net.lecousin.framework.serialization.Deserializer;
import net.lecousin.framework.serialization.SerializationClass;
import net.lecousin.framework.serialization.SerializationContext;
import net.lecousin.framework.serialization.SerializationException;
import net.lecousin.framework.serialization.SerializationSpecWriter;
import net.lecousin.framework.serialization.Serializer;
import net.lecousin.framework.serialization.TypeDefinition;
import net.lecousin.framework.serialization.SerializationContext.AttributeContext;
import net.lecousin.framework.serialization.annotations.AddAttribute;
import net.lecousin.framework.serialization.annotations.Instantiate;
import net.lecousin.framework.serialization.annotations.Instantiation;
import net.lecousin.framework.serialization.annotations.MergeAttributes;
import net.lecousin.framework.serialization.annotations.Rename;
import net.lecousin.framework.serialization.annotations.SerializationMethods;
import net.lecousin.framework.serialization.annotations.SerializationName;
import net.lecousin.framework.serialization.annotations.Transient;
import net.lecousin.framework.serialization.annotations.TypeInstantiation;
import net.lecousin.framework.serialization.annotations.TypeSerializationMethod;
import net.lecousin.framework.serialization.annotations.TypeSerializer;
import net.lecousin.framework.serialization.rules.SerializationRule;
import net.lecousin.framework.math.TimeUnit;
import net.lecousin.framework.text.CharArrayString;
import net.lecousin.framework.text.CharArrayStringBuffer;
import net.lecousin.framework.text.IString;
import net.lecousin.framework.util.ClassUtil;
import net.lecousin.framework.util.Factory;
import net.lecousin.framework.util.Pair;

import org.junit.Assert;
import org.junit.Test;

public abstract class TestSerialization extends LCCoreAbstractTest {

	protected abstract Serializer createSerializer();
	protected abstract Deserializer createDeserializer();
	protected abstract SerializationSpecWriter createSpecWriter();
	
	protected Logger logger = LoggerFactory.get(TestSerialization.class);
	
	@Test
	public void testBasics() {
		Deserializer des = createDeserializer();
		des.setMaximumTextSize(1);
		Assert.assertEquals(1, des.getMaximumTextSize());
	}
	
	@Test
	public void testIOError() {
		Deserializer des = createDeserializer();
		AsyncSupplier<?, SerializationException> res = des.deserialize(new TypeDefinition(String.class), new TestIOError.ReadableAlwaysError(), new ArrayList<>(0));
		res.block(15000);
		Assert.assertNotNull(res.getError());
	}
	
	private void testPrimitive(Object value, Class<?> type) throws Exception {
		MemoryIO io = new MemoryIO(1024, "test");
		io.lockClose();
		Serializer s = createSerializer();
		IAsync<SerializationException> r1 = s.serialize(value, new TypeDefinition(type), io, new ArrayList<>(0));
		r1.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		print(io, value);
		Deserializer d = createDeserializer();
		AsyncSupplier<Object, SerializationException> r2 = d.deserialize(new TypeDefinition(type), io, new ArrayList<>(0));
		r2.blockThrow(0);
		Assert.assertEquals(value, r2.getResult());
		testSpec(type, io);
		io.unlockClose();
	}
	
	private void testPrimitiveNull(Class<?> primitiveType, Class<?> wrapperType) throws Exception {
		MemoryIO io = new MemoryIO(1024, "test");
		io.lockClose();
		Serializer s = createSerializer();
		IAsync<SerializationException> r1 = s.serialize(null, new TypeDefinition(wrapperType), io, new ArrayList<>(0));
		r1.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		print(io, null);
		Deserializer d = createDeserializer();
		AsyncSupplier<Object, SerializationException> r2 = d.deserialize(new TypeDefinition(primitiveType), io, new ArrayList<>(0));
		try {
			r2.blockThrow(0);
			throw new AssertionError("Error expected when deserializing null value to primitive type " + primitiveType.getName());
		} catch (SerializationException e) {
			// ok
		} finally {
			io.unlockClose();
		}
	}
	
	private void testWrongType(Class<?> targetType) throws Exception {
		//testWrongType(targetType, new TestWrongType());
		if (!CharSequence.class.isAssignableFrom(targetType)) {
			testWrongType(targetType, "string");
			if (!targetType.equals(boolean.class) && !targetType.equals(Boolean.class))
				testWrongType(targetType, Boolean.TRUE);
		}
	}
	
	private void testWrongType(Class<?> targetType, Object wrongValue) throws Exception {
		MemoryIO io = new MemoryIO(1024, "test");
		io.lockClose();
		Serializer s = createSerializer();
		IAsync<SerializationException> r1 = s.serialize(wrongValue, new TypeDefinition(wrongValue.getClass()), io, new ArrayList<>(0));
		r1.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		print(io, null);
		Deserializer d = createDeserializer();
		AsyncSupplier<Object, SerializationException> r2 = d.deserialize(new TypeDefinition(targetType), io, new ArrayList<>(0));
		try {
			r2.blockThrow(0);
			throw new AssertionError("Error expected when deserializing from type " + wrongValue.getClass().getName() + " to " + targetType.getName());
		} catch (SerializationException e) {
			// ok
		} finally {
			io.unlockClose();
		}
	}
	
	/** Structure to test booleans. */
	public static class TestBooleans {
		public boolean attr1 = false;
		public boolean attr2 = true;
		public Boolean attr3 = Boolean.FALSE;
		public Boolean attr4 = Boolean.TRUE;
		public Boolean attr5 = Boolean.FALSE;
	}
	
	public static TestBooleans createBooleans() {
		TestBooleans b = new TestBooleans();
		b.attr1 = true;
		b.attr2 = false;
		b.attr3 = Boolean.TRUE;
		b.attr4 = Boolean.FALSE;
		return b;
	}
	
	@Test
	public void testBooleans() throws Exception {
		test(Boolean.TRUE, Boolean.class);
		test(Boolean.FALSE, Boolean.class);
		test(null, Boolean.class);
		testWrongType(Boolean.class);
		test(createBooleans(), TestBooleans.class);
		TestBooleans t = createBooleans();
		t.attr3 = null;
		test(t, TestBooleans.class);
	}
	
	@Test
	public void testBooleanPrimitive() throws Exception {
		testPrimitive(Boolean.TRUE, boolean.class);
		testPrimitive(Boolean.FALSE, boolean.class);
		testPrimitiveNull(boolean.class, Boolean.class);
		testWrongType(boolean.class);
	}
	
	public static class TestNumbers {
		public byte b1 = 2;
		public Byte b2 = Byte.valueOf((byte)-11);
		public short s1 = 51;
		public Short s2 = Short.valueOf((short)-23);
		public int i1 = -111;
		public Integer i2 = Integer.valueOf(222);
		public long l1 = 1234567L;
		public Long l2 = Long.valueOf(-987654321L);
		public float f1 = 0.0123f;
		public Float f2 = Float.valueOf(-9.87654321f);
		public double d1 = -1.112233d;
		public Double d2 = Double.valueOf(9.887766d);
		public BigInteger bi1 = new BigInteger("1234567890");
		public BigInteger bi2 = new BigInteger("-987654321");
		public BigDecimal bd1 = new BigDecimal("0.112233445566778899");
		public BigDecimal bd2 = new BigDecimal("-1.998877665544332211");
	}
	
	public static TestNumbers createNumbers() {
		TestNumbers n = new TestNumbers();
		n.b1 = -45;
		n.b2 = Byte.valueOf((byte)67);
		n.s1 = -15;
		n.s2 = Short.valueOf((short)32);
		n.i1 = 333;
		n.i2 = Integer.valueOf(-444);
		n.l1 = -1234567890L;
		n.l2 = Long.valueOf(9876543210L);
		n.f1 = -0.00112233f;
		n.f2 = Float.valueOf(9.88776655f);
		n.d1 = 2.33445566d;
		n.d2 = Double.valueOf(-99.88777666d);
		n.bi1 = new BigInteger("-9876543210");
		n.bi2 = new BigInteger("51234567890");
		n.bd1 = new BigDecimal("-0.00112233445566778899");
		n.bd2 = new BigDecimal("3.998877665544332211");
		return n;
	}

	@Test
	public void testNumbers() throws Exception {
		test(Byte.valueOf((byte)0), Byte.class);
		test(Byte.valueOf((byte)1), Byte.class);
		test(Byte.valueOf((byte)123), Byte.class);
		test(Byte.valueOf((byte)-1), Byte.class);
		test(Byte.valueOf((byte)-123), Byte.class);
		test(null, Byte.class);
		testWrongType(Byte.class);
		test(Short.valueOf((short)0), Short.class);
		test(Short.valueOf((short)10), Short.class);
		test(Short.valueOf((short)-2340), Short.class);
		test(null, Short.class);
		testWrongType(Short.class);
		test(Integer.valueOf(0), Integer.class);
		test(Integer.valueOf(-12345), Integer.class);
		test(Integer.valueOf(54321), Integer.class);
		test(null, Integer.class);
		testWrongType(Integer.class);
		test(Long.valueOf(0), Long.class);
		test(Long.valueOf(123456789L), Long.class);
		test(Long.valueOf(987654321L), Long.class);
		test(null, Long.class);
		testWrongType(Long.class);
		test(Float.valueOf(0f), Float.class);
		test(Float.valueOf(450.678f), Float.class);
		test(Float.valueOf(-0.0000111f), Float.class);
		test(null, Float.class);
		testWrongType(Float.class);
		test(Double.valueOf(0d), Double.class);
		test(Double.valueOf(1122330d), Double.class);
		test(Double.valueOf(-1.234567890d), Double.class);
		test(null, Double.class);
		testWrongType(Double.class);
		test(createNumbers(), TestNumbers.class);
		TestNumbers t = createNumbers();
		t.b2 = null;
		t.s2 = null;
		t.i2 = null;
		t.l2 = null;
		t.f2 = null;
		t.d2 = null;
		t.bi2 = null;
		t.bd2 = null;
		test(t, TestNumbers.class);
	}
	
	@Test
	public void testNumberPrimitives() throws Exception {
		testPrimitive(Byte.valueOf((byte)23), byte.class);
		testPrimitive(Byte.valueOf((byte)-9), byte.class);
		testPrimitive(Short.valueOf((short)345), short.class);
		testPrimitive(Short.valueOf((short)-987), short.class);
		testPrimitive(Integer.valueOf(123456), int.class);
		testPrimitive(Integer.valueOf(-987654), int.class);
		testPrimitive(Long.valueOf(999999988888L), long.class);
		testPrimitive(Long.valueOf(-777777666666L), long.class);
		testPrimitive(Float.valueOf(0.0000001f), float.class);
		testPrimitive(Float.valueOf(-0.0000001f), float.class);
		testPrimitive(Float.valueOf(12345.9876f), float.class);
		testPrimitive(Float.valueOf(-12345.9876f), float.class);
		testPrimitive(Double.valueOf(0.0000001d), double.class);
		testPrimitive(Double.valueOf(-0.0000001d), double.class);
		testPrimitive(Double.valueOf(12345.9876d), double.class);
		testPrimitive(Double.valueOf(-12345.9876d), double.class);
		testPrimitiveNull(byte.class, Byte.class);
		testPrimitiveNull(short.class, Short.class);
		testPrimitiveNull(int.class, Integer.class);
		testPrimitiveNull(long.class, Long.class);
		testPrimitiveNull(float.class, Float.class);
		testPrimitiveNull(double.class, Double.class);
		testWrongType(byte.class);
		testWrongType(short.class);
		testWrongType(int.class);
		testWrongType(long.class);
		testWrongType(float.class);
		testWrongType(double.class);
	}
	
	
	public static class TestString {
		public String str = "1";
	}
	
	public static class MyString implements CharSequence {

		private CharSequence s;
		
		public MyString(int anotherConstructor) {
			this.s = "error!" + anotherConstructor;
		}
		
		public MyString(int anotherConstructor, long another) {
			this.s = "error!" + anotherConstructor + " / " + another;
		}
		
		public MyString(CharSequence s) {
			this.s = s;
		}
		
		@Override
		public int length() {
			return s.length();
		}

		@Override
		public char charAt(int index) {
			return s.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new MyString(s.subSequence(start, end));
		}
		
		@Override
		public String toString() {
			return s.toString();
		}
		
	}
	
	public static class MyString2 implements CharSequence {

		private String s;
		
		public MyString2(String s) {
			this.s = s;
		}
		
		@Override
		public int length() {
			return s.length();
		}

		@Override
		public char charAt(int index) {
			return s.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new MyString(s.subSequence(start, end));
		}
		
		@Override
		public String toString() {
			return s;
		}
		
	}
	
	public static class MyStringNoConstructor implements CharSequence {

		private CharSequence s;
		
		public MyStringNoConstructor(int anotherConstructor) {
			this.s = "error!" + anotherConstructor;
		}
		
		public MyStringNoConstructor(int anotherConstructor, long another) {
			this.s = "error!" + anotherConstructor + " / " + another;
		}
		
		@Override
		public int length() {
			return s.length();
		}

		@Override
		public char charAt(int index) {
			return s.charAt(index);
		}

		@Override
		public CharSequence subSequence(int start, int end) {
			return new MyString(s.subSequence(start, end));
		}
		
		@Override
		public String toString() {
			return s.toString();
		}
		
	}
	
	public static class TestMyString {
		public MyString str;
	}
	
	public static class TestMyString2 {
		public MyString2 str;
	}
	
	public static class TestMyStringNoConstructor {
		public MyStringNoConstructor str;
	}
	
	public void testString(String s) throws Exception {
		TestString ts = new TestString();
		ts.str = s;
		test(ts, TestString.class);
		test(s, String.class);
	}

	public static class TestIString {
		public CharArrayStringBuffer str;
	}

	public void testIString(String s) throws Exception {
		TestIString ts = new TestIString();
		ts.str = s == null ? null : new CharArrayStringBuffer(s);
		test(ts, TestIString.class);
		test(null, CharArrayString.class);
	}
	
	@Test
	public void testStrings() throws Exception {
		test("Hello World!", String.class);
		test("", String.class);
		testString("hello");
		testString("123");
		testString("a\tb\rc\nd\be\\fg\"hi\'jk&#{([-|_@)]=+}£$*%!:/;.,?<012>34");
		testString(null);
		testIString("hello");
		testIString("123");
		testIString("a\tb\rc\nd\be\\fg\"hi\'jk&#{([-|_@)]=+}£$*%!:/;.,?<012>34");
		testIString(null);
		testWrongType(String.class);

		MemoryIO io = serializeInMemory("Hello!", new TypeDefinition(String.class));
		IString is = deserialize(io, new TypeDefinition(IString.class));
		Assert.assertEquals("Hello!", is.toString());
		
		TestString ts = new TestString();
		ts.str = "Hello World!";
		io = serializeInMemory(ts, new TypeDefinition(TestString.class));
		TestMyString tms = deserialize(io, new TypeDefinition(TestMyString.class));
		Assert.assertEquals("Hello World!", tms.str.toString());

		io = serializeInMemory("Hello!", new TypeDefinition(String.class));
		MyString ms = deserialize(io, new TypeDefinition(MyString.class));
		Assert.assertEquals("Hello!", ms.toString());

		io = serializeInMemory(ts, new TypeDefinition(TestString.class));
		TestMyString2 tms2 = deserialize(io, new TypeDefinition(TestMyString2.class));
		Assert.assertEquals("Hello World!", tms2.str.toString());

		io = serializeInMemory("Hello!", new TypeDefinition(String.class));
		MyString2 ms2 = deserialize(io, new TypeDefinition(MyString2.class));
		Assert.assertEquals("Hello!", ms2.toString());

		io = serializeInMemory(ts, new TypeDefinition(TestString.class));
		try {
			TestMyStringNoConstructor tmsnc = deserialize(io, new TypeDefinition(TestMyStringNoConstructor.class));
			throw new AssertionError("Error expected");
		} catch (SerializationException e) {
			// ok
		}

		io = serializeInMemory("Hello!", new TypeDefinition(String.class));
		try {
			MyStringNoConstructor msnc = deserialize(io, new TypeDefinition(MyStringNoConstructor.class));
		} catch (SerializationException e) {
			// ok
		}

	}
	
	
	public static class TestChar {
		public char c = '1';
		public Character C = Character.valueOf('2');
	}
	
	public void testChar(char c) throws Exception {
		test(Character.valueOf('A'), Character.class);
		TestChar tc = new TestChar();
		tc.c = c;
		tc.C = Character.valueOf(c);
		test(tc, TestChar.class);
		tc.C = null;
		test(tc, TestChar.class);
		testPrimitive(Character.valueOf(c), char.class);
	}
	
	@Test
	public void testChars() throws Exception {
		testChar('0');
		testChar('3');
		testChar('c');
		testChar('R');
		testChar('&');
		testChar('#');
		testChar('\'');
		testChar('"');
		testChar('\\');
		testChar('$');
		testChar('%');
		testChar('.');
		testChar('?');
		testChar(':');
		testChar('/');
		testChar('<');
		testChar('>');
		testChar('!');
		testChar('\n');
		testChar('\r');
		testChar('\t');
		testChar('\b');
		testChar('\f');
		testPrimitiveNull(char.class, Character.class);
		testWrongType(char.class);
		testWrongType(Character.class);
		
		MemoryIO io = serializeInMemory("", new TypeDefinition(String.class));
		Character c = deserialize(io, new TypeDefinition(Character.class));
		Assert.assertNull(c);
	}
	
	public static enum Enum1 { VAL1, VAL2, VAL3 };
	public static enum Enum2 { VAL11, VAL22, VAL33 };
	
	@Test
	public void testEnum() throws Exception {
		test(Enum1.VAL2, Enum1.class);
		test(Enum2.VAL33, Enum2.class);
		test(null, Enum1.class);
		testWrongType(Enum1.class);
	}
	
	public static class TestSimpleObjects {
		public TestBooleans booleans;
		public int i = 51;
		public TestNumbers numbers;
		public String s = "hello";
		public TestString string;
		public TestChar ch;
		public Enum1 e1;
		public Enum2 e2;
	}
	
	public static class TestWrongType {
		public int wrong = 51;
		public TestSimpleObjects simple = new TestSimpleObjects();
	}
	
	@Test
	public void testSimpleObjects() throws Exception {
		testWrongType(TestSimpleObjects.class);
	}
	
	@Test
	public void testRuleError() throws Exception {
		SerializationRule ruleError = new SerializationRule() {
			@Override
			public boolean apply(SerializationClass type, SerializationContext context, List<SerializationRule> rules, boolean serializing) throws SerializationException {
				return false;
			}

			@Override
			public boolean isEquivalent(SerializationRule rule) {
				return false;
			}
			
			@Override
			public Object getDeserializationValue(Object value, TypeDefinition type, SerializationContext context) throws SerializationException {
				if (value instanceof TestSimpleObjects)
					throw new SerializationException("Test error");
				return value;
			}
		};
		
		TestSimpleObjects toSerialize = new TestSimpleObjects();
		MemoryIO io = serializeInMemory(toSerialize, new TypeDefinition(TestSimpleObjects.class));
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		Deserializer d = createDeserializer();
		AsyncSupplier<Object, SerializationException> r2 = d.deserialize(new TypeDefinition(TestSimpleObjects.class), io, Arrays.asList(ruleError));
		try {
			r2.blockThrow(0);
			throw new AssertionError("Error expected when deserializing");
		} catch (SerializationException e) {
			// ok
		} finally {
			io.unlockClose();
		}
	}
	
	public static class TestLists {
		public List<Boolean> booleans;
		public List<Integer> integers;
		public List<Float> floats;
		public List<String> strings;
		public List<Character> characters;
		public List<TestBooleans> testBooleans;
		public List<TestNumbers> testNumbers;
	}
	
	@Test
	public void testLists() throws Exception {
		TestLists t;
		t = new TestLists();
		t.booleans = Arrays.asList(Boolean.TRUE, Boolean.TRUE, Boolean.FALSE, Boolean.TRUE, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, Boolean.TRUE);
		t.integers = Arrays.asList(Integer.valueOf(12), Integer.valueOf(-98), Integer.valueOf(18347));
		t.floats = Arrays.asList(Float.valueOf(0.1234f), Float.valueOf(-823.674f), Float.valueOf(11.22f));
		t.strings = Arrays.asList("hello", "world", "!!!");
		t.characters = Arrays.asList(Character.valueOf('H'), Character.valueOf('e'), Character.valueOf('L'), Character.valueOf('l'), Character.valueOf('O'));
		t.testBooleans = Arrays.asList(createBooleans(), new TestBooleans(), null);
		t.testNumbers = Arrays.asList(createNumbers(), new TestNumbers(), null, createNumbers());
		test(t, TestLists.class);
		
		t = new TestLists();
		t.booleans = new LinkedList<>();
		t.integers = new LinkedList<>();
		t.floats = new LinkedList<>();
		t.strings = new LinkedList<>();
		t.characters = new LinkedList<>();
		t.testBooleans = new LinkedList<>();
		Random rand = new Random();
		for (int i = 0; i < 1000; ++i) {
			t.booleans.add(Boolean.valueOf(rand.nextBoolean()));
			t.integers.add(Integer.valueOf(rand.nextInt()));
			t.floats.add(Float.valueOf(rand.nextFloat()));
			t.strings.add("test" + rand.nextInt());
			t.characters.add(Character.valueOf((char)('a' + rand.nextInt(10))));
			t.testBooleans.add(createBooleans());
		}
		test(t, TestLists.class);
		
		MemoryIO io;
		try {
			ArrayList<Integer> list = new ArrayList<>();
			list.add(Integer.valueOf(20));
			io = serializeInMemory(list, new TypeDefinition(ArrayList.class));
			deserialize(io, new TypeDefinition(ArrayList.class));
			throw new AssertionError("Error expected when specifying no parameter for ArrayList");
		} catch (SerializationException e) {
			// ok
		}
		
		io = serializeInMemory(null, new TypeDefinition(ArrayList.class, new TypeDefinition(Integer.class)));
		ArrayList<Integer> al = deserialize(io, new TypeDefinition(ArrayList.class, new TypeDefinition(Integer.class)));
		Assert.assertNull(al);
		
		try {
			ArrayList<Integer> list = new ArrayList<>();
			list.add(Integer.valueOf(20));
			io = serializeInMemory(list, new TypeDefinition(ArrayList.class, new TypeDefinition(Integer.class)));
			deserialize(io, new TypeDefinition(ArrayList.class, new TypeDefinition(TestString.class)));
			throw new AssertionError("Error expected when specifying incompatible parameter for ArrayList");
		} catch (SerializationException e) {
			// ok
		}
	}
	
	public static class TestArrays {
		public boolean[] b1;
		public Boolean[] b2;
		public Integer[] i;
		public long[] l;
		public Float[] f;
		public float[] ff;
		public short[] s;
		public double[] d;
		public String[] strings;
		public char[] chars;
		public byte[] bytes;
		public TestBooleans[] testBooleans;
		public TestNumbers[] testNumbers;
	}
	
	@Test
	public void testArrays() throws Exception {
		TestArrays t = new TestArrays();
		t.b1 = new boolean[] { true, true, false, true, false, false };
		t.b2 = new Boolean[] { Boolean.FALSE, Boolean.TRUE };
		t.i = new Integer[] { Integer.valueOf(55), Integer.valueOf(-123), Integer.valueOf(12345) };
		t.l = new long[] { 1111, -2222, 345, -678 };
		t.f = new Float[] { Float.valueOf(-0.01234f), Float.valueOf(9.87654f) };
		t.d = new double[] { 11.223344d, -99.887766 };
		t.ff = new float[] { 123.456f, 987.654f };
		t.s = new short[] { 1234, -9876 };
		t.strings = new String[] { "Hello", "World", "!", "Salut\ntoi" };
		t.chars = new char[] { 'Q', '&', '<', '=', '"', '\n', '\'', ']' };
		t.bytes = new byte[] { 1, 2, 3, 4, 5, 6 };
		t.testBooleans = new TestBooleans[] { new TestBooleans(), createBooleans() };
		t.testNumbers = new TestNumbers[] { createNumbers(), new TestNumbers() };
		test(t, TestArrays.class);
		test(new int[] { 11, 33, 55, 77, 99 }, int[].class);
		test(new byte[] { 11, 33, 55, 77, 99 }, byte[].class);
		
		Random rand = new Random();
		int[] integers = new int[1000];
		for (int i = 0; i < integers.length; ++i)
			integers[i] = rand.nextInt();
		test(integers, int[].class);
		
		testWrongType(int[].class);
		testWrongType(int[].class, new boolean[] { true, false });
	}
	
	public static class TestListOfList {
		public List<List<List<Integer>>> list;
	}
	
	@Test
	public void testListOfList() throws Exception {
		TestListOfList t = new TestListOfList();
		t.list = Arrays.asList(
			Arrays.asList(
				Arrays.asList(Integer.valueOf(123), Integer.valueOf(456))
			),
			new ArrayList<>(),
			Arrays.asList(
				Arrays.asList(Integer.valueOf(987), Integer.valueOf(654), Integer.valueOf(321))
			),
			new LinkedList<>()
		);
		test(t, TestListOfList.class);
	}
	
	public static class TestMap {
		public Map<String, Long> myMap;
	}
	
	@Test
	public void testMap() throws Exception {
		TestMap t = new TestMap();
		t.myMap = new HashMap<>();
		t.myMap.put("key1", Long.valueOf(111));
		t.myMap.put("key2", Long.valueOf(222));
		t.myMap.put("key3", Long.valueOf(333));
		test(t, TestMap.class);
		Map<Integer,String> map = new HashMap<>();
		map.put(Integer.valueOf(1), "Hello");
		map.put(Integer.valueOf(2), "World");
		map.put(Integer.valueOf(3), "!");
		map.put(Integer.valueOf(4), null);
		MemoryIO io = serializeInMemory(map, new TypeDefinition(HashMap.class, new TypeDefinition(Integer.class), new TypeDefinition(String.class)));
		print(io, map);
		Map<Integer,String> map2 = deserialize(io, new TypeDefinition(HashMap.class, new TypeDefinition(Integer.class), new TypeDefinition(String.class)));
		checkMap(map, map2);
	}
	
	public static class TestIO {
		public InputStream stream;
		public IO.Readable io;
	}
	
	@Test
	public void testIO() throws Exception {
		TestIO t = new TestIO();
		t.stream = new ByteArrayInputStream("This is an InputStream".getBytes(StandardCharsets.UTF_8));
		t.stream.mark(0);
		t.io = new ByteArrayIO("This is an IO.Readable".getBytes(StandardCharsets.UTF_8), "test");
		testInMemory(t, TestIO.class);
		t.stream.reset();
		((ByteArrayIO)t.io).seekSync(SeekType.FROM_BEGINNING, 0);
		testInFile(t, TestIO.class);
		
		test(null, IO.Readable.class);
		test(null, InputStream.class);
	}
	
	public static class IntegerUnitAsString {
		public String value;
	}
	public static class IntegerUnitAsByte {
		@Unit(TimeUnit.Hour.class)
		public Byte value;
	}
	public static class IntegerUnitAsByte2 {
		@Unit(TimeUnit.Hour.class)
		public byte value;
	}
	public static class IntegerUnitAsInt {
		@Unit(TimeUnit.Hour.class)
		public Integer value;
	}
	public static class IntegerUnitAsInt2 {
		@Unit(TimeUnit.Hour.class)
		public int value;
	}
	public static class IntegerUnitAsShort {
		@Unit(TimeUnit.Hour.class)
		public Short value;
	}
	public static class IntegerUnitAsShort2 {
		@Unit(TimeUnit.Hour.class)
		public short value;
	}
	public static class IntegerUnitAsLong {
		@Unit(TimeUnit.Hour.class)
		public Long value;
	}
	public static class IntegerUnitAsLong2 {
		@Unit(TimeUnit.Hour.class)
		public long value;
	}
	public static class IntegerUnitAsBigInteger {
		@Unit(TimeUnit.Hour.class)
		public BigInteger value;
	}
	
	@Test
	public void testIntegerUnit() throws Exception {
		testIntegerUnit("3 days", Long.valueOf(3L * 24));
		testIntegerUnit("6h", Long.valueOf(6L));
		testIntegerUnit("16 hours", Long.valueOf(16L));
		testIntegerUnit("31", Long.valueOf(31L));
	}

	private void testIntegerUnit(String str, Long val) throws Exception {
		MemoryIO io;
		IntegerUnitAsString s;
		IntegerUnitAsLong l;
		s = new IntegerUnitAsString();
		s.value = str;
		io = serializeInMemory(s, new TypeDefinition(IntegerUnitAsString.class));
		io.lockClose();
		print(io, s);
		
		l = deserialize(io, new TypeDefinition(IntegerUnitAsLong.class));
		Assert.assertEquals(val.longValue(), l.value.longValue());
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsLong2 l2 = deserialize(io, new TypeDefinition(IntegerUnitAsLong2.class));
		Assert.assertEquals(val.longValue(), l2.value);
		
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsByte b = deserialize(io, new TypeDefinition(IntegerUnitAsByte.class));
		Assert.assertEquals(val.byteValue(), b.value.byteValue());
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsByte2 b2 = deserialize(io, new TypeDefinition(IntegerUnitAsByte2.class));
		Assert.assertEquals(val.byteValue(), b2.value);
		
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsShort us = deserialize(io, new TypeDefinition(IntegerUnitAsShort.class));
		Assert.assertEquals(val.shortValue(), us.value.shortValue());
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsShort2 us2 = deserialize(io, new TypeDefinition(IntegerUnitAsShort2.class));
		Assert.assertEquals(val.shortValue(), us2.value);
		
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsInt ui = deserialize(io, new TypeDefinition(IntegerUnitAsInt.class));
		Assert.assertEquals(val.intValue(), ui.value.intValue());
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsInt2 ui2 = deserialize(io, new TypeDefinition(IntegerUnitAsInt2.class));
		Assert.assertEquals(val.intValue(), ui2.value);
		
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		IntegerUnitAsBigInteger bi = deserialize(io, new TypeDefinition(IntegerUnitAsBigInteger.class));
		Assert.assertEquals(val.longValue(), bi.value.longValue());
		
		testSpec(IntegerUnitAsString.class, io);
		io.unlockClose();
	}
	
	public static interface MyInterface {}
	
	public static class MyImplementation implements MyInterface {}
	public static class MyImplementation2 implements MyInterface {}
	
	public static class MyContainerOfAbstracts {
		public MyInterface interf;
		@Instantiate(factory=MyImplementation2AttributeFactory.class)
		public MyInterface interf2;
	}
	
	public static class MyImplementation2AttributeFactory implements Factory<MyInterface, AttributeContext> {
		@Override
		public MyInterface create(AttributeContext discriminator) {
			return new MyImplementation2();
		}
	}
	
	@Test
	public void testInstantiate() throws Exception {
		MyImplementation impl = new MyImplementation();
		MemoryIO io = new MemoryIO(1024, "test");
		Serializer s = createSerializer();
		IAsync<SerializationException> res = s.serialize(impl, new TypeDefinition(MyInterface.class), io, new ArrayList<>(0));
		res.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		print(io, impl);
		MyInterface interf = deserialize(io, new TypeDefinition(MyInterface.class));
		Assert.assertEquals(MyImplementation.class, interf.getClass());
		
		MyContainerOfAbstracts t = new MyContainerOfAbstracts();
		t.interf = new MyImplementation();
		t.interf2 = new MyImplementation();
		io = serializeInMemory(t, new TypeDefinition(MyContainerOfAbstracts.class));
		print(io, t);
		MyContainerOfAbstracts t2 = deserialize(io, new TypeDefinition(MyContainerOfAbstracts.class));
		Assert.assertEquals(MyImplementation.class, t2.interf.getClass());
		Assert.assertEquals(MyImplementation2.class, t2.interf2.getClass());
	}
	
	public static class TestWithTransient {
		public boolean b1 = true;
		public transient boolean b2 = false;
		public int i1 = 10;
		@Transient
		public int i2 = 20;
	}
	public static class TestWithoutTransient {
		public boolean b1 = true;
		public boolean b2 = false;
		public int i1 = 10;
		public int i2 = 20;
	}
	
	@Test
	public void testTransient() throws Exception {
		TestWithTransient t1 = new TestWithTransient();
		t1.b1 = false;
		t1.b2 = true;
		t1.i1 = 99;
		t1.i2 = 88;
		MemoryIO io = serializeInMemory(t1, new TypeDefinition(TestWithTransient.class));
		print(io, t1);
		TestWithTransient t2 = deserialize(io, new TypeDefinition(TestWithTransient.class));
		Assert.assertFalse(t2.b1);
		Assert.assertFalse(t2.b2);
		Assert.assertEquals(99, t2.i1);
		Assert.assertEquals(20, t2.i2);
		TestWithoutTransient t3 = new TestWithoutTransient();
		t3.b1 = false;
		t3.b2 = true;
		t3.i1 = 99;
		t3.i2 = 88;
		io = serializeInMemory(t3, new TypeDefinition(TestWithoutTransient.class));
		print(io, t3);
		TestWithTransient t4 = deserialize(io, new TypeDefinition(TestWithTransient.class));
		Assert.assertFalse(t4.b1);
		Assert.assertFalse(t4.b2);
		Assert.assertEquals(99, t4.i1);
		Assert.assertEquals(20, t4.i2);
	}
	
	public static class TestRename1 {
		@SerializationName("world")
		public String hello;
	}
	
	public static class TestRename2 {
		public String world;
	}
	
	@Test
	public void testRenameAttribute() throws Exception {
		TestRename1 t1 = new TestRename1();
		t1.hello = "bonjour";
		test(t1, TestRename1.class);
		MemoryIO ioMem = serializeInMemory(t1, new TypeDefinition(TestRename1.class));
		TestRename2 t2 = deserialize(ioMem, new TypeDefinition(TestRename2.class));
		Assert.assertEquals("bonjour", t2.world);
	}
	
	public static class TestRenamePair1 {
		@Rename(value=Pair.class, attribute="value1", newName="myValue1")
		@Rename(value=Pair.class, attribute="value2", newName="myValue2")
		public Pair<String,String> pair;
	}
	
	public static class TestRenamePair2 {
		public MyPair pair;
	}
	public static class MyPair {
		public String myValue1;
		public String myValue2;
	}
	
	@Test
	public void testRenamePair() throws Exception {
		TestRenamePair1 t1 = new TestRenamePair1();
		t1.pair = new Pair<>("Hello", "World");
		test(t1, TestRenamePair1.class);
		MemoryIO ioMem = serializeInMemory(t1, new TypeDefinition(TestRenamePair1.class));
		TestRenamePair2 t2 = deserialize(ioMem, new TypeDefinition(TestRenamePair2.class));
		Assert.assertEquals(t1.pair.getValue1(), t2.pair.myValue1);
		Assert.assertEquals(t1.pair.getValue2(), t2.pair.myValue2);
	}
	
	public static class TestNoDefaultConstructor1 {
		public TestNoDefaultConstructor1(String value) {
			this.value = value;
		}
		
		public String value;
		
		@Override
		public String toString() {
			return value;
		}
	}
	
	public static class TestNoDefaultConstructor1Serializer implements CustomSerializer {
		@Override
		public TypeDefinition sourceType() {
			return new TypeDefinition(TestNoDefaultConstructor1.class);
		}
		@Override
		public TypeDefinition targetType() {
			return new TypeDefinition(String.class);
		}
		@Override
		public Object serialize(Object src, Object containerInstance) {
			return ((TestNoDefaultConstructor1)src).value;
		}
		@Override
		public Object deserialize(Object src, Object containerInstance) {
			return new TestNoDefaultConstructor1((String)src);
		}
	}
	
	@TypeSerializer(TestNoDefaultConstructor1Serializer.class)
	public static class TestTypeSerializer1 {
		public TestNoDefaultConstructor1 test;
	}

	public static class TestTypeSerializer2 {
		@TypeSerializer(TestNoDefaultConstructor1Serializer.class)
		public TestNoDefaultConstructor1 test;
	}
	
	@Test
	public void testTypeSerializer() throws Exception {
		TestTypeSerializer1 t = new TestTypeSerializer1();
		t.test = new TestNoDefaultConstructor1("Hello");
		test(t, TestTypeSerializer1.class);
		TestTypeSerializer2 t2 = new TestTypeSerializer2();
		t.test = new TestNoDefaultConstructor1("World");
		test(t2, TestTypeSerializer2.class);
	}
	
	public static class TestTypeSerializationMethod {
		@TypeSerializationMethod("toString")
		public TestNoDefaultConstructor1 test;
	}

	@Test
	public void testTypeSerializationMethod() throws Exception {
		TestTypeSerializationMethod t = new TestTypeSerializationMethod();
		t.test = new TestNoDefaultConstructor1("World");
		test(t, TestTypeSerializationMethod.class);
	}
	
	public static class TestSerializationMethods {
		@SerializationMethods(serialization="testToString", deserialization="testFromString")
		public TestNoDefaultConstructor1 test;
		
		public String testToString() { return test.value; }
		public TestNoDefaultConstructor1 testFromString(String s) { return new TestNoDefaultConstructor1(s); }
	}

	@Test
	public void testSerializationMethods() throws Exception {
		TestSerializationMethods t = new TestSerializationMethods();
		t.test = new TestNoDefaultConstructor1("Hello World!");
		test(t, TestSerializationMethods.class);
	}
	
	public static class Merged {
		public String aString;
	}
	
	public static class ToMerge {
		@MergeAttributes(type=Pair.class, target="value2")
		public Pair<String, Merged> pair;
	}
	
	public static class MergedPair {
		public String aString;
		public String value1;
	}

	public static class TestMerged {
		public MergedPair pair;
	}

	public static class ToMerge2 {
		@Rename(value=Pair.class, attribute="value1", newName="theMerged")
		@MergeAttributes(type=Pair.class, target="value2")
		public Pair<String, Merged> pair;
	}
	
	public static class MergedPair2 {
		public String aString;
		public String theMerged;
	}

	public static class TestMerged2 {
		public MergedPair2 pair;
	}
	
	@Test
	public void testMergeAttributes() throws Exception {
		Merged merged = new Merged();
		merged.aString = "Hello";
		ToMerge toMerge = new ToMerge();
		toMerge.pair = new Pair<>("World", merged);
		test(toMerge, ToMerge.class);
		MemoryIO ioMem = serializeInMemory(toMerge, new TypeDefinition(ToMerge.class));
		TestMerged testMerged = deserialize(ioMem, new TypeDefinition(TestMerged.class));
		Assert.assertEquals(toMerge.pair.getValue1(), testMerged.pair.value1);
		Assert.assertEquals(toMerge.pair.getValue2().aString, testMerged.pair.aString);

		ToMerge2 toMerge2 = new ToMerge2();
		toMerge2.pair = new Pair<>("World", merged);
		test(toMerge2, ToMerge2.class);
		ioMem = serializeInMemory(toMerge2, new TypeDefinition(ToMerge2.class));
		TestMerged2 testMerged2 = deserialize(ioMem, new TypeDefinition(TestMerged2.class));
		Assert.assertEquals(toMerge2.pair.getValue1(), testMerged2.pair.theMerged);
		Assert.assertEquals(toMerge2.pair.getValue2().aString, testMerged2.pair.aString);
	}
	
	public static class ToMerge3 {
		@Rename(value=Pair.class, attribute="value1", newName="theMerged")
		@MergeAttributes(type=Pair.class, target="value2")
		public List<Pair<String, Merged>> list;
	}
	
	public static class Merged3 {
		public List<MergedPair2> list;
	}

	@Test
	public void testMergeAttributesOfListElements() throws Exception {
		ToMerge3 t = new ToMerge3();
		t.list = new ArrayList<>();
		Merged m = new Merged();
		m.aString = "Hello";
		t.list.add(new Pair<>("bonjour", m));
		m = new Merged();
		m.aString = "World";
		t.list.add(new Pair<>("le monde", m));
		test(t, ToMerge3.class);
	}

	@Test
	public void testMergeAttributesOfListElements2() throws Exception {
		ToMerge3 t = new ToMerge3();
		t.list = new ArrayList<>();
		Merged m = new Merged();
		m.aString = "Hello";
		t.list.add(new Pair<>("bonjour", m));
		m = new Merged();
		m.aString = "World";
		t.list.add(new Pair<>("le monde", m));
		MemoryIO io = serializeInMemory(t, new TypeDefinition(ToMerge3.class));
		print(io, t);
		deserialize(io, new TypeDefinition(Merged3.class));
	}
	
	@AddAttribute(name="config", deserializer="configure", serializer="getConfiguration")
	public static class TestAddAttribute {
		public String hello;
		public transient int value = 1;
		public void configure(int val) {
			this.value = val + 1;
		}
		@Transient
		public int getConfiguration() {
			return value;
		}
	}
	
	public static class TestAddAttributeContainer {
		public TestAddAttribute test;
	}
	
	@Test
	public void testAddAttribute() throws Exception {
		TestAddAttributeContainer t = new TestAddAttributeContainer();
		t.test = new TestAddAttribute();
		t.test.hello = "World";
		t.test.value = 51;
		MemoryIO ioMem = serializeInMemory(t, new TypeDefinition(TestAddAttributeContainer.class));
		TestAddAttributeContainer t2 = deserialize(ioMem, new TypeDefinition(TestAddAttributeContainer.class));
		Assert.assertEquals("World", t2.test.hello);
		Assert.assertEquals(52, t2.test.value);
	}
	
	@TypeInstantiation(factory=MyInterfaceToInstantiateProvider.class)
	public static interface MyInterfaceToInstantiate {}
	
	public static class MyImplementationSerialized implements MyInterfaceToInstantiate {
		public String hello = "a";
	}

	public static class MyImplementationDeserialized implements MyInterfaceToInstantiate {
		public String hello = "b";
	}
	
	public static class MyInterfaceToInstantiateProvider implements Supplier<MyInterfaceToInstantiate> {
		@Override
		public MyInterfaceToInstantiate get() {
			return new MyImplementationDeserialized();
		}
	}
	
	public static class MyInterfaceToInstantiateContainer {
		public MyInterfaceToInstantiate test;
	}
	
	@Test
	public void testTypeInstantiationContainer() throws Exception {
		MyInterfaceToInstantiateContainer t = new MyInterfaceToInstantiateContainer();
		t.test = new MyImplementationSerialized();
		((MyImplementationSerialized)t.test).hello = "World";
		MemoryIO ioMem = serializeInMemory(t, new TypeDefinition(MyInterfaceToInstantiateContainer.class));
		print(ioMem, t);
		MyInterfaceToInstantiateContainer o2 = deserialize(ioMem, new TypeDefinition(MyInterfaceToInstantiateContainer.class));
		Assert.assertFalse(o2.test == null);
		Assert.assertEquals(MyImplementationDeserialized.class, o2.test.getClass());
		Assert.assertEquals("World", ((MyImplementationDeserialized)o2.test).hello);
	}

	@Test
	public void testTypeInstantiationValue() throws Exception {
		MyImplementationSerialized o = new MyImplementationSerialized();
		o.hello = "World";
		MemoryIO ioMem = serializeInMemory(o, new TypeDefinition(MyInterfaceToInstantiate.class));
		print(ioMem, o);
		MyInterfaceToInstantiate o2 = deserialize(ioMem, new TypeDefinition(MyInterfaceToInstantiate.class));
		Assert.assertFalse(o2 == null);
		Assert.assertEquals(MyImplementationDeserialized.class, o2.getClass());
		Assert.assertEquals("World", ((MyImplementationDeserialized)o2).hello);
	}
	
	public static class InstantiationContainer {
		public String d = "";
		@Instantiation(discriminator="d", factory=InstantiationFactory.class)
		public MyInterface i;
	}
	
	public static class InstantiationFactory implements Factory<MyInterface, String> {
		@Override
		public MyInterface create(String discriminator) {
			if ("test1".equals(discriminator))
				return new MyImplementation();
			if ("test2".equals(discriminator))
				return new MyImplementation2();
			return null;
		}
	}
	
	@Test
	public void testInstantiation() throws Exception {
		InstantiationContainer t = new InstantiationContainer();
		t.d = "test1";
		t.i = new MyImplementation2();
		MemoryIO ioMem = serializeInMemory(t, new TypeDefinition(InstantiationContainer.class));
		print(ioMem, t);
		InstantiationContainer t2 = deserialize(ioMem, new TypeDefinition(InstantiationContainer.class));
		Assert.assertFalse(t2.i == null);
		Assert.assertEquals(MyImplementation.class, t2.i.getClass());

		t = new InstantiationContainer();
		t.d = "test1";
		t.i = new MyImplementation();
		ioMem = serializeInMemory(t, new TypeDefinition(InstantiationContainer.class));
		print(ioMem, t);
		t2 = deserialize(ioMem, new TypeDefinition(InstantiationContainer.class));
		Assert.assertFalse(t2.i == null);
		Assert.assertEquals(MyImplementation.class, t2.i.getClass());

		t = new InstantiationContainer();
		t.d = "test2";
		t.i = new MyImplementation();
		ioMem = serializeInMemory(t, new TypeDefinition(InstantiationContainer.class));
		print(ioMem, t);
		t2 = deserialize(ioMem, new TypeDefinition(InstantiationContainer.class));
		Assert.assertFalse(t2.i == null);
		Assert.assertEquals(MyImplementation2.class, t2.i.getClass());
	}
	
	
	protected <T> void test(T object, Class<T> type) throws Exception {
		testInMemory(object, type);
		testInFile(object, type);
	}

	protected <T> void testInMemory(T object, Class<T> type) throws Exception {
		MemoryIO ioMem = serializeInMemory(object, new TypeDefinition(type));
		ioMem.lockClose();
		print(ioMem, object);
		T o2 = deserialize(ioMem, new TypeDefinition(type));
		check(object, o2);
		testSpec(type, ioMem);
		ioMem.unlockClose();
	}

	protected <T> void testInFile(T object, Class<T> type) throws Exception {
		FileIO.ReadWrite ioFile = serializeInFile(object, new TypeDefinition(type));
		print(ioFile, object);
		T o2 = deserialize(ioFile, new TypeDefinition(type));
		check(object, o2);
	}

	protected MemoryIO serializeInMemory(Object o, TypeDefinition type) throws Exception {
		MemoryIO io = new MemoryIO(1024, "test");
		Serializer s = createSerializer();
		IAsync<SerializationException> res = s.serialize(o, type, io, new ArrayList<>(0));
		res.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		return io;
	}
	
	protected FileIO.ReadWrite serializeInFile(Object o, TypeDefinition type) throws Exception {
		File tmp = File.createTempFile("test", "serialization");
		FileIO.ReadWrite io = new FileIO.ReadWrite(tmp, Task.Priority.NORMAL);
		Serializer s = createSerializer();
		IAsync<SerializationException> res = s.serialize(o, type, io, new ArrayList<>(0));
		res.blockThrow(0);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		return io;
	}
	
	@SuppressWarnings("unchecked")
	protected <T> T deserialize(IO.Readable io, TypeDefinition type) throws Exception {
		Deserializer d = createDeserializer();
		AsyncSupplier<Object, SerializationException> res = d.deserialize(type, io, new ArrayList<>(0));
		res.blockThrow(0);
		return (T)res.getResult();
	}
	
	protected void testSpec(Class<?> type, MemoryIO serialization) throws Exception {
		SerializationSpecWriter sw = createSpecWriter();
		if (sw == null) return;
		MemoryIO io = new MemoryIO(1024, "test");
		sw.writeSpecification(type, io, new ArrayList<>(0)).blockThrow(0);
		printSpec(io, type);
		checkSpec(io, type, serialization);
	}
	
	protected void checkValue(Object expected, Object found) throws Exception {
		if (expected == null) {
			Assert.assertTrue("found is not null but expected is null", found == null);
			return;
		}
		Assert.assertFalse("found is null but expected is not null", found == null);
		Class<?> type = expected.getClass();
		if (type.isArray()) {
			checkArray(expected, found);
			return;
		}
		if (type.isPrimitive() ||
			Number.class.isAssignableFrom(type) ||
			Boolean.class.equals(type) ||
			Character.class.equals(type)) {
			Assert.assertEquals(expected, found);
			return;
		}
		if (CharSequence.class.isAssignableFrom(type)) {
			Assert.assertEquals(((CharSequence)expected).toString(), ((CharSequence)found).toString());
			return;
		}
		if (List.class.isAssignableFrom(type)) {
			checkList((List<?>)expected, (List<?>)found);
			return;
		}
		if (Map.class.isAssignableFrom(type)) {
			checkMap((Map<?,?>)expected, (Map<?,?>)found);
			return;
		}
		if (InputStream.class.isAssignableFrom(type)) {
			checkInputStream((InputStream)expected, (InputStream)found);
			return;
		}
		if (IO.Readable.class.isAssignableFrom(type)) {
			checkIO((IO.Readable.Seekable)expected, (IO.Readable)found);
			return;
		}
		check(expected, found);
	}
	
	protected void check(Object expected, Object found) throws Exception {
		if (expected == null) {
			Assert.assertTrue(found == null);
			return;
		}
		Assert.assertEquals(expected.getClass(), found.getClass());
		if (expected.getClass().isPrimitive()) return;
		if (expected.getClass().equals(String.class)) return;
		for (Field f : ClassUtil.getAllFields(expected.getClass())) {
			if ((f.getModifiers() & Modifier.STATIC) != 0) continue;
			if ((f.getModifiers() & Modifier.FINAL) != 0) continue;
			Method getter1 = ClassUtil.getGetter(expected.getClass(), f.getName());
			Object o1;
			if (getter1 != null) o1 = getter1.invoke(expected);
			else o1 = f.get(expected);
			Method getter2 = ClassUtil.getGetter(found.getClass(), f.getName());
			Object o2;
			if (getter2 != null) o2 = getter2.invoke(found);
			else o2 = f.get(found);
			try {
				checkValue(o1, o2);
			} catch (Throwable e) {
				e.printStackTrace();
				throw new Exception("Error in field " + f.getName() + " of object " + found.getClass().getName(), e);
			}
		}
	}
	
	protected void checkList(List<?> l1, List<?> l2) throws Exception {
		Assert.assertEquals(l1.size(), l2.size());
		Iterator<?> it1 = l1.iterator();
		Iterator<?> it2 = l2.iterator();
		while (it1.hasNext())
			checkValue(it1.next(), it2.next());
	}
	
	protected void checkArray(Object expected, Object found) throws Exception {
		Assert.assertEquals(Array.getLength(expected), Array.getLength(found));
		int l = Array.getLength(expected);
		for (int i = 0; i < l; ++i)
			checkValue(Array.get(expected, i), Array.get(found, i));
	}

	protected void checkMap(Map<?,?> m1, Map<?,?> m2) throws Exception {
		Assert.assertEquals(m1.size(), m2.size());
		for (Map.Entry<?,?> e : m1.entrySet())
			checkValue(e.getValue(), m2.get(e.getKey()));
	}
	
	protected void checkInputStream(InputStream stream1, InputStream stream2) throws Exception {
		stream1.reset();
		String s1 = IOUtil.readFullyAsStringSync(stream1, StandardCharsets.UTF_8);
		String s2 = IOUtil.readFullyAsStringSync(stream2, StandardCharsets.UTF_8);
		Assert.assertEquals(s1, s2);
	}
	
	protected void checkIO(IO.Readable.Seekable io1, IO.Readable io2) throws Exception {
		io1.seekSync(SeekType.FROM_BEGINNING, 0);
		String s1 = IOUtil.readFullyAsStringSync(io1, StandardCharsets.UTF_8);
		String s2 = IOUtil.readFullyAsStringSync(io2, StandardCharsets.UTF_8);
		Assert.assertEquals(s1, s2);
	}
	
	protected void print(IO.Readable.Seekable io, Object o) throws Exception {
		String content = IOUtil.readFullyAsStringSync(io, StandardCharsets.UTF_8);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		logger.trace("Serialization result for " + (o == null ? "null" : o.getClass().getName()) + "\r\n" + content);
	}
	
	protected void printSpec(IO.Readable.Seekable io, Class<?> type) throws Exception {
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		String content = IOUtil.readFullyAsStringSync(io, StandardCharsets.UTF_8);
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		logger.trace("Serialization specification for " + type.getName() + "\r\n" + content);
	}
	
	@SuppressWarnings("unused")
	protected void checkSpec(IO.Readable.Seekable spec, Class<?> type, IO.Readable.Seekable serialization) throws Exception {
	}
	
	// --- Error cases ---
	
	@TypeInstantiation(factory=InvalidTypeInstantiationFactory.class)
	public static abstract class InvalidTypeInstantiation {
	}
	
	public static abstract class InvalidTypeInstantiationFactory implements Supplier<InvalidTypeInstantiation> {
	}
	
	public static class InvalidTypeInstantiationContainer {
		public InvalidTypeInstantiation invalid;
	}
	
	@Test
	public void testInvalidTypeInstantiation() throws Exception {
		InvalidTypeInstantiationContainer container = new InvalidTypeInstantiationContainer();
		container.invalid = new InvalidTypeInstantiation() {};
		try {
			testInMemory(container, InvalidTypeInstantiationContainer.class);
		} catch (SerializationException e) {
			if (e.getCause() instanceof InstantiationException)
				return; // OK
			throw e;
		}
	}
}
