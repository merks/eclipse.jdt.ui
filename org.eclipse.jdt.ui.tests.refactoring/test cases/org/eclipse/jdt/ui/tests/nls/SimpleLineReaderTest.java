/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.ui.tests.nls;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.eclipse.jdt.internal.corext.refactoring.nls.SimpleLineReader;

public class SimpleLineReaderTest extends TestCase {
	
	public static TestSuite suite() {
		return new TestSuite(SimpleLineReaderTest.class);
	}
	
    public void testSimpleLineReader() throws Exception {
        SimpleLineReader reader = new SimpleLineReader("aha\noho\r\n\r\n\n");         
        assertEquals("aha\n", reader.readLine()); 
        assertEquals("oho\r\n", reader.readLine()); 
        assertEquals("\r\n", reader.readLine()); 
        assertEquals("\n", reader.readLine()); 
        assertEquals(null, reader.readLine());
    }
    
    public void testSimpleLineReaderWithEmptyString() {
        SimpleLineReader simpleLineReader = new SimpleLineReader(""); 
        assertEquals(null, simpleLineReader.readLine());
    }    
    
    public void testSimpleLineReaderWithEscapedLF() {
        SimpleLineReader simpleLineReader = new SimpleLineReader("a\nb\\nc\n");
        assertEquals("a\n", simpleLineReader.readLine()); 
        assertEquals("b\\nc\n", simpleLineReader.readLine());
        assertEquals(null, simpleLineReader.readLine());
    }
    
    public void testSimpleLineReaderWithEscapedCR() {
        SimpleLineReader simpleLineReader = new SimpleLineReader("a\nb\\rc\r");
        assertEquals("a\n", simpleLineReader.readLine()); 
        assertEquals("b\\rc\r", simpleLineReader.readLine());
        assertEquals(null, simpleLineReader.readLine());
    }
    
    public void testSimpleLineReaderWithCR() {
        SimpleLineReader simpleLineReader = new SimpleLineReader("a\rb\r");
        assertEquals("a\r", simpleLineReader.readLine()); 
        assertEquals("b\r", simpleLineReader.readLine());
        assertEquals(null, simpleLineReader.readLine());
    }

    public void testSimpleLineReaderWithoutNL() {
        SimpleLineReader simpleLineReader = new SimpleLineReader("="); 
        assertEquals("=", simpleLineReader.readLine()); 
        assertEquals(null, simpleLineReader.readLine());
    }
    
    public void testSimpleLineReaderWithMissingNL() {
        SimpleLineReader simpleLineReader = new SimpleLineReader("a\rb");
        assertEquals("a\r", simpleLineReader.readLine()); 
        assertEquals("b", simpleLineReader.readLine());
        assertEquals(null, simpleLineReader.readLine());
    }
}
