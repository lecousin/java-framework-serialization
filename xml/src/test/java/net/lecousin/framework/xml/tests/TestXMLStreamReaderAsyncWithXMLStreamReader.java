package net.lecousin.framework.xml.tests;

import java.util.Collection;

import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.xml.XMLStreamReaderAsync;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestXMLStreamReaderAsyncWithXMLStreamReader extends TestXMLStreamEventsWithXMLStreamReader<XMLStreamReaderAsync> {

	@Parameters(name = "file = {0}")
	public static Collection<Object[]> parameters() {
		return getFiles();
	}
	
	public TestXMLStreamReaderAsyncWithXMLStreamReader(String filepath) {
		super(filepath);
	}
	
	@Override
	protected XMLStreamReaderAsync start(IO.Readable input) throws Exception {
		XMLStreamReaderAsync xml = new XMLStreamReaderAsync(input, 1024, 8);
		xml.start().blockException(0);
		return xml;
	}
	
	@Override
	protected void next(XMLStreamReaderAsync xml) throws Exception {
		xml.next().blockException(0);
	}

}
