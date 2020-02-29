package net.lecousin.framework.json;

import java.io.EOFException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import net.lecousin.framework.concurrent.threads.Task;
import net.lecousin.framework.core.test.LCCoreAbstractTest;
import net.lecousin.framework.io.IO.Seekable.SeekType;
import net.lecousin.framework.io.IOUtil;
import net.lecousin.framework.io.buffering.ByteArrayIO;
import net.lecousin.framework.io.buffering.MemoryIO;
import net.lecousin.framework.io.text.BufferedReadableCharacterStream;
import net.lecousin.framework.json.JSONReaderAsync.EventType;

import org.junit.Assert;
import org.junit.Test;

public class TestJSON extends LCCoreAbstractTest {

	@SuppressWarnings({ "unchecked" })
	@Test
	public void test() throws Exception {
		MemoryIO io = new MemoryIO(8192, "testJSON");
		JSONWriter writer = new JSONWriter(io, true);
		writer.openObject();
		writer.addObjectAttribute("bool", true);
		writer.addObjectAttribute("int", Integer.valueOf(123));
		writer.addObjectAttribute("long");
		writer.writeNumber(Long.valueOf(-98765));
		writer.addObjectAttribute("double");
		writer.writeNumber(Double.valueOf(-123.456e123d));
		writer.addObjectAttribute("float");
		writer.writeStringContent("10E+20");
		writer.addObjectAttribute("str");
		writer.writeString("this is a string");
		writer.addObjectAttribute("str2");
		writer.writeString("a\tstring\nwith\rsome\\characters to\bescape\0îæàü");
		writer.addObjectAttribute("str3");
		writer.startString();
		writer.writeStringContent("string3");
		writer.endString();
		writer.addObjectAttribute("arr");
		writer.openArray();
		writer.startNextArrayElement();
		writer.writeBoolean(false);
		writer.startNextArrayElement();
		writer.writeNull();
		writer.closeArray();
		writer.addObjectAttribute("obj");
		writer.openObject();
		writer.closeObject();
		writer.closeObject();
		writer.flush().blockThrow(0);

		io.seekSync(SeekType.FROM_BEGINNING, 0);
		System.out.println("Generated JSON:");
		System.out.println(IOUtil.readFullyAsStringSync(io, StandardCharsets.UTF_8));
		
		io.seekSync(SeekType.FROM_BEGINNING, 0);
		Object o = JSONParser.parse(new BufferedReadableCharacterStream(io, StandardCharsets.UTF_8, 8192, 2), Task.Priority.NORMAL).blockResult(0);
		Assert.assertTrue(o instanceof Map);
		Map<String, Object> obj = (Map<String, Object>)o;
		Assert.assertEquals(10, obj.size());
		Assert.assertTrue(((Boolean)obj.get("bool")).booleanValue());
		Assert.assertEquals(123, ((Number)obj.get("int")).intValue());
		Assert.assertEquals(-98765, ((Number)obj.get("long")).longValue());
		Assert.assertEquals(-123.456e123d, ((Number)obj.get("double")).doubleValue(), 0);
		Assert.assertEquals(10.0e20f, ((Number)obj.get("float")).floatValue(), 0);
		Assert.assertEquals("this is a string", obj.get("str"));
		Assert.assertEquals("a\tstring\nwith\rsome\\characters to\bescape\0îæàü", obj.get("str2"));
		Assert.assertEquals("string3", obj.get("str3"));
		o = obj.get("arr");
		Assert.assertTrue(o instanceof List);
		List<Object> arr = (List<Object>)o;
		Assert.assertEquals(2, arr.size());
		Assert.assertFalse(((Boolean)arr.get(0)).booleanValue());
		Assert.assertNull(arr.get(1));
		o = obj.get("obj");
		Assert.assertTrue(o instanceof Map);
		obj = (Map<String, Object>)o;
		Assert.assertEquals(0, obj.size());
		io.close();
	}
	
	@Test
	public void basicTests() throws Exception {
		ByteArrayIO io = new ByteArrayIO("  true  ".getBytes(StandardCharsets.UTF_8), "test");
		JSONReaderAsync reader = new JSONReaderAsync(io, StandardCharsets.UTF_8);
		reader.getMaximumTextSize();
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.BOOLEAN, reader.event);
		Assert.assertTrue(reader.bool.booleanValue());
		io.close();

		io = new ByteArrayIO("\"this is a test\"".getBytes(StandardCharsets.UTF_8), "test");
		reader = new JSONReaderAsync(io, StandardCharsets.UTF_8);
		reader.setMaximumTextSize(4);
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.STRING, reader.event);
		Assert.assertEquals("this", reader.string.asString());
		io.close();

		io = new ByteArrayIO("\"\r\t\n\b\f\f\b\n\t\r\"".getBytes(StandardCharsets.UTF_8), "test");
		reader = new JSONReaderAsync(io, StandardCharsets.UTF_8);
		reader.setMaximumTextSize(5);
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.STRING, reader.event);
		Assert.assertEquals("\r\t\n\b\f", reader.string.asString());
		io.close();
		io = new ByteArrayIO("\"\r\t\n\b\f\f\b\n\t\r\"".getBytes(StandardCharsets.UTF_8), "test");
		Assert.assertEquals("\r\t\n\b\f\f\b\n\t\r", JSONParser.parse(new BufferedReadableCharacterStream(io, StandardCharsets.UTF_8, 256, 1), Task.Priority.NORMAL).blockResult(0));
		io.close();

		io = new ByteArrayIO("\"\\\"\\\\\\/\\f\\a\"".getBytes(StandardCharsets.UTF_8), "test");
		reader = new JSONReaderAsync(io, StandardCharsets.UTF_8);
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.STRING, reader.event);
		Assert.assertEquals("\"\\/\fa", reader.string.asString());
		io.close();
		io = new ByteArrayIO("\"\\\"\\\\\\/\\f\\a\"".getBytes(StandardCharsets.UTF_8), "test");
		Assert.assertEquals("\"\\/\fa", JSONParser.parse(new BufferedReadableCharacterStream(io, StandardCharsets.UTF_8, 256, 1), Task.Priority.NORMAL).blockResult(0));
		io.close();

		io = new ByteArrayIO(" { \"name\" : 51 , \"second\" \t : \r\n 52\r\n\t }".getBytes(StandardCharsets.UTF_8), "test");
		reader = new JSONReaderAsync(io, StandardCharsets.UTF_8);
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.START_OBJECT, reader.event);
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.START_ATTRIBUTE, reader.event);
		Assert.assertEquals("name", reader.string.asString());
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.NUMBER, reader.event);
		Assert.assertEquals(51, reader.number.intValue());
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.START_ATTRIBUTE, reader.event);
		Assert.assertEquals("second", reader.string.asString());
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.NUMBER, reader.event);
		Assert.assertEquals(52, reader.number.intValue());
		reader.next().blockThrow(0);
		Assert.assertEquals(EventType.END_OBJECT, reader.event);
		io.close();
		
		// exception
		new JSONParsingException("test");
		new JSONParsingException("test", new Exception());
	}
	
	@Test
	public void testErrors() throws Exception {
		Assert.assertEquals(Boolean.TRUE, testOk("true"));
		Assert.assertEquals(Boolean.FALSE, testOk("false"));
		Assert.assertNull(testOk("null"));
		System.out.println("**** Start test of errors *****");
		testKo("truf");
		testKo("tru");
		testKo("trkk");
		testKo("tr");
		testKo("tddd");
		testKo("t");
		testKo("falsf");
		testKo("fals");
		testKo("faltt");
		testKo("fal");
		testKo("farrr");
		testKo("fa");
		testKo("fffff");
		testKo("f");
		testKo("nult");
		testKo("nul");
		testKo("nutt");
		testKo("nu");
		testKo("niii");
		testKo("n");
		testKo("a");
		testKo("[");
		testKo("[true");
		testKo("[true,");
		testKo("[true;");
		testKo("{");
		testKo("{\"a\"");
		testKo("{a");
		testKo("{\"a\":");
		testKo("{\"a\",");
		testKo("{\"a\":true");
		testKo("{\"a\":true,");
		testKo("{\"a\":true;");
		testKo("{\"a\":-");
		testKo("{\"a\":-1");
		testKo("x");
		testKo("!");
		testKo("\"\\uZZZZ\"");
		System.out.println("**** End test of errors *****");
	}
	
	private static Object testOk(String json) throws Exception {
		ByteArrayIO io = new ByteArrayIO(json.getBytes(StandardCharsets.UTF_8), "test");
		Object o = JSONParser.parse(new BufferedReadableCharacterStream(io, StandardCharsets.UTF_8, 8192, 2), Task.Priority.NORMAL).blockResult(0);
		io.close();
		return o;
	}
	
	private static void testKo(String json) throws Exception {
		ByteArrayIO io = new ByteArrayIO(json.getBytes(StandardCharsets.UTF_8), "test");
		try {
			JSONParser.parse(new BufferedReadableCharacterStream(io, StandardCharsets.UTF_8, 8192, 2), Task.Priority.NORMAL).blockResult(0);
		} catch (JSONParsingException e) {
			// ok
		} catch (EOFException e) {
			// ok
		}
		io.close();
	}
}
