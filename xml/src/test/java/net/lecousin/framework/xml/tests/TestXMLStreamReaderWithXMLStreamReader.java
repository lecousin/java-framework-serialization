package net.lecousin.framework.xml.tests;

import java.util.Collection;

import net.lecousin.framework.core.test.runners.LCConcurrentRunner;
import net.lecousin.framework.io.IO;
import net.lecousin.framework.xml.XMLStreamReader;

import org.junit.runner.RunWith;
import org.junit.runners.Parameterized.Parameters;

@RunWith(LCConcurrentRunner.Parameterized.class) @org.junit.runners.Parameterized.UseParametersRunnerFactory(LCConcurrentRunner.ConcurrentParameterizedRunnedFactory.class)
public class TestXMLStreamReaderWithXMLStreamReader extends TestXMLStreamEventsWithXMLStreamReader<XMLStreamReader> {

	@Parameters(name = "file = {0}")
	public static Collection<Object[]> parameters() {
		return getFiles();
	}
	
	public TestXMLStreamReaderWithXMLStreamReader(String filepath) {
		super(filepath);
	}
	
	
	@Override
	protected XMLStreamReader start(IO.Readable input) throws Exception {
		XMLStreamReader xml = new XMLStreamReader(input, 1024, 8);
		xml.start();
		return xml;
	}
	
	@Override
	protected void next(XMLStreamReader xml) throws Exception {
		xml.next();
	}

}
