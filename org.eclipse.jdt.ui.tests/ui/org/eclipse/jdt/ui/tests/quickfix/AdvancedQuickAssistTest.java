/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *   Konstantin Scheglov (scheglov_ke@nlmk.ru) - initial API and implementation 
 *          (report 71244: New Quick Assist's [quick assist])
 *******************************************************************************/
package org.eclipse.jdt.ui.tests.quickfix;

import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import junit.framework.Test;
import junit.framework.TestSuite;

import org.eclipse.jdt.testplugin.JavaProjectHelper;
import org.eclipse.jdt.testplugin.TestOptions;

import org.eclipse.core.runtime.Preferences;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.text.templates.persistence.TemplateStore;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.tests.core.ProjectTestSetup;

import org.eclipse.jdt.internal.corext.template.java.CodeTemplateContextType;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.internal.ui.text.correction.AssistContext;
import org.eclipse.jdt.internal.ui.text.correction.CUCorrectionProposal;

public class AdvancedQuickAssistTest extends QuickFixTest {

	private static final Class THIS= AdvancedQuickAssistTest.class;

	private IJavaProject fJProject1;
	private IPackageFragmentRoot fSourceFolder;


	public AdvancedQuickAssistTest(String name) {
		super(name);
	}

	public static Test allTests() {
		return new ProjectTestSetup(new TestSuite(THIS));
	}

	public static Test setUpTest(Test test) {
		return new ProjectTestSetup(test);
	}

	public static Test suite() {
		if (true) {
			return allTests();
		}
		return setUpTest(new AdvancedQuickAssistTest("testAssignToLocal1"));
	}


	protected void setUp() throws Exception {
		Hashtable options= TestOptions.getFormatterOptions();
		options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
		options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "4");

		JavaCore.setOptions(options);

		IPreferenceStore store= JavaPlugin.getDefault().getPreferenceStore();
		store.setValue(PreferenceConstants.CODEGEN_ADD_COMMENTS, false);
		store.setValue(PreferenceConstants.CODEGEN_KEYWORD_THIS, false);

		TemplateStore codeTemplateStore= JavaPlugin.getDefault().getCodeTemplateStore();
		codeTemplateStore.findTemplate(CodeTemplateContextType.METHODSTUB).setPattern("//TODO\n${body_statement}");

		Preferences corePrefs= JavaCore.getPlugin().getPluginPreferences();
		corePrefs.setValue(JavaCore.CODEASSIST_FIELD_PREFIXES, "");
		corePrefs.setValue(JavaCore.CODEASSIST_STATIC_FIELD_PREFIXES, "");
		corePrefs.setValue(JavaCore.CODEASSIST_FIELD_SUFFIXES, "");
		corePrefs.setValue(JavaCore.CODEASSIST_STATIC_FIELD_SUFFIXES, "");

		fJProject1= ProjectTestSetup.getProject();

		fSourceFolder= JavaProjectHelper.addSourceContainer(fJProject1, "src");
	}


	protected void tearDown() throws Exception {
		JavaProjectHelper.clear(fJProject1, ProjectTestSetup.getDefaultClasspath());
	}

	public void testSplitIfCondition1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (a && (b == 0)) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("&&");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (a) {\n");
		buf.append("            if (b == 0) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testSplitIfCondition2() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0) && c) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("&& (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a) {\n");
		buf.append("            if (b == 0 && c) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testSplitIfCondition3() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0) && c) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("&& c");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0)) {\n");
		buf.append("            if (c) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testJoinAndIfStatements1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0)) {\n");
		buf.append("            if (c) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (a");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0) && c) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testJoinAndIfStatements2() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0)) {\n");
		buf.append("            if (c) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (a");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a && (b == 0) && c) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testJoinOrIfStatements1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a)\n");
		buf.append("            return;\n");
		buf.append("        if (b == 5)\n");
		buf.append("            return;\n");
		buf.append("        b= 9;\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset1= buf.toString().indexOf("if (a");
		int offset2= buf.toString().lastIndexOf("b= 9;");
		AssistContext context= getCorrectionContext(cu, offset1, offset2 - offset1);
		List proposals= collectAssists(context, false);

		for (Iterator I= proposals.iterator(); I.hasNext();) {
			Object o= I.next();
			if (!(o instanceof CUCorrectionProposal))
				I.remove();
		}

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a || b == 5)\n");
		buf.append("            return;\n");
		buf.append("        b= 9;\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testSplitOrCondition1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a || b == 5)\n");
		buf.append("            return;\n");
		buf.append("        b= 9;\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("||");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		for (Iterator I= proposals.iterator(); I.hasNext();) {
			Object o= I.next();
			if (!(o instanceof CUCorrectionProposal))
				I.remove();
		}

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, boolean c) {\n");
		buf.append("        if (a)\n");
		buf.append("            return;\n");
		buf.append("        if (b == 5)\n");
		buf.append("            return;\n");
		buf.append("        b= 9;\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testIfReturnIntoIfElseAtEndOfVoidMethod1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (a) {\n");
		buf.append("            b= 9;\n");
		buf.append("            return;\n");
		buf.append("        }\n");
		buf.append("        b= 0;\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (a) {\n");
		buf.append("            b= 9;\n");
		buf.append("        } else {\n");
		buf.append("            b= 0;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testInverseIfContinueIntoIfThenInLoops1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("import java.util.*;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, ArrayList list) {\n");
		buf.append("        for (Iterator I = list.iterator(); I.hasNext();) {\n");
		buf.append("            if (a) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("import java.util.*;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, ArrayList list) {\n");
		buf.append("        for (Iterator I = list.iterator(); I.hasNext();) {\n");
		buf.append("            if (!a)\n");
		buf.append("                continue;\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testInverseIfIntoContinueInLoops1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("import java.util.*;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, ArrayList list) {\n");
		buf.append("        for (Iterator I = list.iterator(); I.hasNext();) {\n");
		buf.append("            if (!a)\n");
		buf.append("                continue;\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("import java.util.*;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, ArrayList list) {\n");
		buf.append("        for (Iterator I = list.iterator(); I.hasNext();) {\n");
		buf.append("            if (a) {\n");
		buf.append("                b= 9;\n");
		buf.append("            }\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testRemoveExtraParenthesis1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, Object o) {\n");
		buf.append("        if (a && (b == 0) && (o instanceof Integer) && (a || b)) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset1= buf.toString().indexOf("if (");
		int offset2= buf.toString().indexOf(") {", offset1);
		AssistContext context= getCorrectionContext(cu, offset1, offset2 - offset1);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, Object o) {\n");
		buf.append("        if (a && b == 0 && o instanceof Integer && (a || b)) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testAddParanoidalParenthesis1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, int c, Object o) {\n");
		buf.append("        if (a && b == 0 && b + c > 3 && o instanceof Integer) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset1= buf.toString().indexOf("if (");
		int offset2= buf.toString().indexOf(") {", offset1);
		AssistContext context= getCorrectionContext(cu, offset1, offset2 - offset1);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b, int c, Object o) {\n");
		buf.append("        if (a && (b == 0) && (b + c > 3) && (o instanceof Integer)) {\n");
		buf.append("            b= 9;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testInverseIfCondition1() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (a && (b == 0)) {\n");
		buf.append("            return;\n");
		buf.append("        } else {\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, int b) {\n");
		buf.append("        if (!a || (b != 0)) {\n");
		buf.append("        } else {\n");
		buf.append("            return;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

	public void testInverseIfCondition2() throws Exception {
		IPackageFragment pack1= fSourceFolder.createPackageFragment("test1", false, null);
		StringBuffer buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, boolean b, boolean c) {\n");
		buf.append("        if (a || b && c) {\n");
		buf.append("            return;\n");
		buf.append("        } else {\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		ICompilationUnit cu= pack1.createCompilationUnit("E.java", buf.toString(), false, null);

		int offset= buf.toString().indexOf("if (");
		AssistContext context= getCorrectionContext(cu, offset, 0);
		List proposals= collectAssists(context, false);

		assertCorrectLabels(proposals);

		buf= new StringBuffer();
		buf.append("package test1;\n");
		buf.append("public class E {\n");
		buf.append("    public void foo(boolean a, boolean b, boolean c) {\n");
		buf.append("        if (!a && (!b || !c)) {\n");
		buf.append("        } else {\n");
		buf.append("            return;\n");
		buf.append("        }\n");
		buf.append("    }\n");
		buf.append("}\n");
		String expected1= buf.toString();

		assertExpectedExistInProposals(proposals, new String[] {expected1});

	}

}
