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
package org.eclipse.jdt.internal.corext.refactoring.nls;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.PropertyResourceBundle;
import java.util.Set;

import org.eclipse.text.edits.InsertEdit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaModelStatusConstants;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jface.text.Document;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.SourceRange;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.base.JavaStringStatusContext;
import org.eclipse.jdt.internal.corext.refactoring.changes.TextChangeCompatibility;
import org.eclipse.jdt.internal.corext.refactoring.changes.ValidationStateChange;
import org.eclipse.jdt.internal.corext.refactoring.nls.changes.CreateTextFileChange;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusContext;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;

public class NLSRefactoring extends Refactoring {

	public static final String BUNDLE_NAME= "BUNDLE_NAME"; //$NON-NLS-1$
	public static final String PROPERTY_FILE_EXT= ".properties"; //$NON-NLS-1$
	public static final String DEFAULT_ACCESSOR_CLASSNAME= "Messages"; //$NON-NLS-1$
	public static final String KEY= "${key}"; //$NON-NLS-1$

	private static final String DEFAULT_PROPERTY_FILENAME= "messages"; //$NON-NLS-1$

	static final String fgLineDelimiter= System.getProperty("line.separator", "\n"); //$NON-NLS-2$ //$NON-NLS-1$

	private IPath fPropertyFilePath;
	private IPackageFragment fAccessorPackage;
	private String fAccessorClassName= "Messages"; //$NON-NLS-1$
	private String fSubstitutionPattern;
	private ICompilationUnit fCu;
	private final CodeGenerationSettings fCodeGenerationSettings;
	private String fSubstitutionPrefix;
	private NLSHolder fNlsHolder;
	private NLSHint fNlsHint;

	private NLSRefactoring(ICompilationUnit cu, CodeGenerationSettings codeGenerationSettings) {
		Assert.isNotNull(cu);
		Assert.isNotNull(codeGenerationSettings);

		fCu= cu;
		fCodeGenerationSettings= codeGenerationSettings;
		fNlsHolder= NLSHolder.create(cu);
	}

	public static NLSRefactoring create(ICompilationUnit cu, CodeGenerationSettings codeGenerationSettings) {
		if (!isAvailable(cu))
			return null;
		return new NLSRefactoring(cu, codeGenerationSettings);
	}

	public static boolean isAvailable(ICompilationUnit cu) {
		if (cu == null)
			return false;

		if (!cu.exists())
			return false;

		return true;
	}

	/**
	 * no validation is done
	 * 
	 * @param pattern Example: "Messages.getString(${key})". Must not be <code>null</code>.
	 *        should (but does not have to) contain NLSRefactoring.KEY (default value is
	 *        $key$) only the first occurrence of this key will be used
	 */
	public void setSubstitutionPattern(String pattern) {
		Assert.isNotNull(pattern);
		fSubstitutionPattern= pattern;
	}

	/**
	 * to show the pattern in the ui
	 */
	public String getSubstitutionPattern() {
		if (fSubstitutionPattern == null) {
			return getDefaultSubstitutionPattern();
		}
		return fSubstitutionPattern;
	}

	public String getDefaultSubstitutionPattern() {
		return NLSRefactoring.getDefaultSubstitutionPattern(fAccessorClassName);
	}

	public static String getDefaultSubstitutionPattern(String accessorName) {
		return accessorName + ".getString(" + KEY + ")"; //$NON-NLS-2$ //$NON-NLS-1$
	}

	public ICompilationUnit getCu() {
		return fCu;
	}

	public String getName() {
		return NLSMessages.getFormattedString("NLSrefactoring.compilation_unit", fCu.getElementName());//$NON-NLS-1$
	}

	/**
	 * no validation done here full path expected can be null - the default value will be
	 * used to ask what the default value is - use getDefaultPropertyFileName to get the
	 * file name getDefaultPropertyPackageName to get the package name
	 */
	public void setPropertyFilePath(IPath path) {
		Assert.isNotNull(path);
		fPropertyFilePath= path;
	}

	private IPath getPropertyFilePath() {
		return fPropertyFilePath;
	}

	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException {

		if (fNlsHolder.getSubstitutions().length == 0) {
			String message= NLSMessages.getFormattedString("NLSRefactoring.no_strings", fCu.getElementName());//$NON-NLS-1$
			return RefactoringStatus.createFatalErrorStatus(message);
		}
		return new RefactoringStatus();
	}

	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException {
		checkParameters();
		try {

			pm.beginTask(NLSMessages.getString("NLSrefactoring.checking"), 7); //$NON-NLS-1$

			RefactoringStatus result= new RefactoringStatus();

			result.merge(checkIfAnythingToDo());
			if (result.hasFatalError()) {
				return result;
			}
			pm.worked(1);

			result.merge(validateModifiesFiles());
			if (result.hasFatalError()) {
				return result;
			}
			pm.worked(1);

			result.merge(checkSubstitutionPattern());
			pm.worked(1);

			result.merge(checkForDuplicateKeys());
			pm.worked(1);

			result.merge(checkForKeysAlreadyDefined());
			pm.worked(1);

			// TODO check prefix

			result.merge(checkKeys());
			pm.worked(1);

			if (!propertyFileExists() && willModifyPropertyFile()) {
				String msg= NLSMessages.getFormattedString("NLSrefactoring.will_be_created", getPropertyFilePath().toString()); //$NON-NLS-1$
				result.addInfo(msg);
			}
			pm.worked(1);

			return result;
		} finally {
			pm.done();
		}
	}

	public Change createChange(IProgressMonitor pm) throws CoreException {
		try {
			checkParameters();

			pm.beginTask("", 3); //$NON-NLS-1$

			final ValidationStateChange result= new ValidationStateChange(
				NLSMessages.getFormattedString("NLSRefactoring.change.text", fCu.getElementName())); //$NON-NLS-1$

			if (willCreateAccessorClass()) {
				result.add(AccessorClass.create(fCu, getAccessorClassName(), fCodeGenerationSettings, getAccessorCUPath(),
					fAccessorPackage, getPropertyFilePath(), new SubProgressMonitor(pm, 1)));
			}
			pm.worked(1);

			if (willModifySource()) {
				result.add(SourceModification.create(getCu(), fNlsHolder, getDefaultSubstitutionPattern(),
					fSubstitutionPattern, fSubstitutionPrefix, willCreateAccessorClass(), fAccessorPackage,
					getAccessorClassName()));
			}
			pm.worked(1);

			if (willModifyPropertyFile()) {
				Change change= createOrChangePropertyFile();
				if (change != null) {
					result.add(change);
				}
			}
			pm.worked(1);

			return result;
		} finally {
			pm.done();
		}
	}

	private void checkParameters() {
		Assert.isNotNull(fNlsHolder.getSubstitutions());
		Assert.isNotNull(fAccessorPackage);
		Assert.isNotNull(fPropertyFilePath);
		Assert.isNotNull(fNlsHolder.getLines());

		// these values have defaults ...
		Assert.isNotNull(fAccessorClassName);
		Assert.isNotNull(fSubstitutionPattern);
	}

	private IFile[] getAllFilesToModify() {

		List files= new ArrayList(3);
		if (willModifySource()) {
			IFile file= ResourceUtil.getFile(fCu);
			if (file != null)
				files.add(file);
		}

		if (willModifyPropertyFile() && propertyFileExists())
			files.add(getPropertyFile());

		return (IFile[])files.toArray(new IFile[files.size()]);
	}

	private RefactoringStatus validateModifiesFiles() {
		return Checks.validateModifiesFiles(getAllFilesToModify());
	}

	//should stop checking if fatal error
	private RefactoringStatus checkIfAnythingToDo() throws JavaModelException {
		if (willCreateAccessorClass())
			return null;

		if (willModifyPropertyFile())
			return null;

		if (willModifySource())
			return null;

		RefactoringStatus result= new RefactoringStatus();
		result.addFatalError(NLSMessages.getString("NLSrefactoring.nothing_to_do")); //$NON-NLS-1$
		return result;
	}

	private boolean propertyFileExists() {
		return Checks.resourceExists(getPropertyFilePath());
	}

	private RefactoringStatus checkSubstitutionPattern() {
		String pattern= getSubstitutionPattern();

		RefactoringStatus result= new RefactoringStatus();
		if (pattern.trim().length() == 0) {//$NON-NLS-1$ 
			result.addError(NLSMessages.getString("NLSrefactoring.pattern_empty")); //$NON-NLS-1$
		}

		if (pattern.indexOf(KEY) == -1) {
			String msg= NLSMessages.getFormattedString("NLSrefactoring.pattern_does_not_contain", KEY); //$NON-NLS-1$
			result.addWarning(msg);
		}

		if (pattern.indexOf(KEY) != pattern.lastIndexOf(KEY)) {
			String msg= NLSMessages.getFormattedString("NLSrefactoring.Only_the_first_occurrence_of", KEY);//$NON-NLS-1$
			result.addWarning(msg);
		}

		return result;
	}

	private RefactoringStatus checkForKeysAlreadyDefined() throws JavaModelException {
		if (!propertyFileExists())
			return null;
		RefactoringStatus result= new RefactoringStatus();
		PropertyResourceBundle bundle= getPropertyBundle();
		if (bundle == null) {
			return null;
		}

		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();

		for (int i= 0; i < subs.length; i++) {
			String keyWithPrefix= subs[i].getKeyWithPrefix(fSubstitutionPrefix);
			String s= getBundleString(bundle, keyWithPrefix);
			if (s != null) {
				if (!hasSameValue(s, subs[i])) {
					String[] args= {keyWithPrefix, s, subs[i].value.getValue()};
					String msg= NLSMessages.getFormattedString("NLSrefactoring.already_exists", args); //$NON-NLS-1$
					result.addFatalError(msg);
				} else {
					subs[i].putToPropertyFile= false;
					String[] args= {keyWithPrefix, s};
					String msg= NLSMessages.getFormattedString("NLSrefactoring.already_in_bundle", args); //$NON-NLS-1$
					result.addWarning(msg);
				}
			}
		}
		return result;
	}

	private boolean hasSameValue(String val, NLSSubstitution sub) {
		return (val.equals(sub.value.getValue()));
	}

	/**
	 * returns <code>null</code> if not defined
	 */
	private String getBundleString(PropertyResourceBundle bundle, String key) {
		try {
			return bundle.getString(key);
		} catch (MissingResourceException e) {
			return null;
		}
	}

	private PropertyResourceBundle getPropertyBundle() throws JavaModelException {
		InputStream is= getPropertyFileInputStream();
		if (is == null)
			return null;
		try {
			PropertyResourceBundle result= new PropertyResourceBundle(is);
			return result;
		} catch (IOException e1) {
			return null;
		} finally {
			try {
				is.close();
			} catch (IOException e) {
				throw new JavaModelException(e, IJavaModelStatusConstants.IO_EXCEPTION);
			}
		}
	}

	private InputStream getPropertyFileInputStream() throws JavaModelException {
		IFile file= ResourcesPlugin.getWorkspace().getRoot().getFile(getPropertyFilePath());

		try {
			return file.getContents();
		} catch (CoreException e) {
			throw new JavaModelException(e, IJavaModelStatusConstants.CORE_EXCEPTION);
		}
	}

	private RefactoringStatus checkForDuplicateKeys() {
		Map map= new HashMap(); //String (key) -> Set of NLSSubstitution
		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		for (int i= 0; i < subs.length; i++) {
			NLSSubstitution sub= subs[i];
			String key= sub.getKeyWithPrefix(fSubstitutionPrefix);
			if (!map.containsKey(key)) {
				map.put(key, new HashSet());
			}
			((Set)map.get(key)).add(sub);
		}

		RefactoringStatus result= new RefactoringStatus();
		for (Iterator iter= map.keySet().iterator(); iter.hasNext();) {
			Set substitutions= (Set)map.get(iter.next());
			result.merge(checkForDuplicateKeys(substitutions));
		}
		return result;
	}

	/**
	 * all elements in the parameter must be NLSSubstitutions with the same key
	 */
	private RefactoringStatus checkForDuplicateKeys(Set subs) {
		if (subs.size() <= 1)
			return null;

		NLSSubstitution[] toTranslate= getEntriesToTranslate(subs);
		if (toTranslate.length <= 1)
			return null;

		for (int i= 0; i < toTranslate.length; i++) {
			toTranslate[i].putToPropertyFile= (i == 0);
		}

		String value= toTranslate[0].value.getValue();
		for (int i= 0; i < toTranslate.length; i++) {
			NLSSubstitution each= toTranslate[i];
			if (!hasSameValue(value, each)) {
				String msg= NLSMessages.getFormattedString("NLSrefactoring.duplicated", each.key);//$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
		}
		String[] args= {toTranslate[0].key, value};
		String msg= NLSMessages.getFormattedString("NLSrefactoring.reused", args); //$NON-NLS-1$
		return RefactoringStatus.createWarningStatus(msg);
	}

	private static NLSSubstitution[] getEntriesToTranslate(Set subs) {
		List result= new ArrayList(subs.size());
		for (Iterator iter= subs.iterator(); iter.hasNext();) {
			NLSSubstitution each= (NLSSubstitution)iter.next();
			if (each.task == NLSSubstitution.TRANSLATE)
				result.add(each);
		}
		return (NLSSubstitution[])result.toArray(new NLSSubstitution[result.size()]);
	}

	private RefactoringStatus checkKeys() {
		RefactoringStatus result= new RefactoringStatus();
		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		for (int i= 0; i < subs.length; i++) {
			result.merge(checkKey(subs[i].key));
		}
		return result;
	}

	private static RefactoringStatus checkKey(String key) {
		RefactoringStatus result= new RefactoringStatus();

		if (key == null)
			result.addFatalError(NLSMessages.getString("NLSrefactoring.null")); //$NON-NLS-1$

		if (key.startsWith("!") || key.startsWith("#")) { //$NON-NLS-1$ //$NON-NLS-2$
			RefactoringStatusContext context= new JavaStringStatusContext(key, new SourceRange(0, 0));
			result.addWarning(NLSMessages.getString("NLSRefactoring.warning"), context); //$NON-NLS-1$
		}

		if ("".equals(key.trim())) //$NON-NLS-1$
			result.addFatalError(NLSMessages.getString("NLSrefactoring.empty")); //$NON-NLS-1$

		//feature in resource bundle - does not work properly if keys have ":"
		for (int i= 0; i < NLSHolder.UNWANTED_STRINGS.length; i++) {
			if (key.indexOf(NLSHolder.UNWANTED_STRINGS[i]) != -1) {
				String[] args= {key, NLSHolder.UNWANTED_STRINGS[i]};
				String msg= NLSMessages.getFormattedString("NLSrefactoring.should_not_contain", args); //$NON-NLS-1$
				result.addError(msg);
			}
		}
		return result;
	}

	private boolean willCreateAccessorClass() throws JavaModelException {

		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		if (NLSSubstitution.countItems(subs, NLSSubstitution.TRANSLATE) == 0) {
			return false;
		}

		ICompilationUnit compilationUnit= getAccessorCu();
		if (compilationUnit.exists()) {
			return false;
		}

		if (typeNameExistsInPackage(fAccessorPackage, fAccessorClassName)) {
			return false;
		}

		return (!Checks.resourceExists(getAccessorCUPath()));
	}

	private ICompilationUnit getAccessorCu() {
		return fAccessorPackage.getCompilationUnit(getAccessorCUName());
	}

	private boolean willModifySource() {
		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		if (NLSSubstitution.countItems(subs, NLSSubstitution.SKIP) != subs.length)
			return true;

		if (willAddImportDeclaration())
			return true;

		return false;
	}

	private boolean willModifyPropertyFile() {
		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		return NLSSubstitution.countItems(subs, NLSSubstitution.TRANSLATE) > 0;
	}

	private boolean willAddImportDeclaration() {
		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		if (NLSSubstitution.countItems(subs, NLSSubstitution.TRANSLATE) == 0) {
			return false;
		}

		return true;
	}

	//---- resource bundle file
	private Change createOrChangePropertyFile() throws JavaModelException {
		if (!propertyFileExists()) {
			return new CreateTextFileChange(getPropertyFilePath(), createPropertyFileSource(), "8859_1", "txt"); //$NON-NLS-1$ //$NON-NLS-2$
		}

		String name= NLSMessages.getFormattedString("NLSrefactoring.Append_to_property_file", getPropertyFilePath().toString()); //$NON-NLS-1$
		TextChange tfc= new TextFileChange(name, getPropertyFile());

		try {
			addChanges(tfc, fNlsHolder.getSubstitutions(), fSubstitutionPrefix);
		} catch (Exception e) {
			// error while creating some of the changes
		}

		return tfc;
	}

	private IFile getPropertyFile() {
		return (IFile)(ResourcesPlugin.getWorkspace().getRoot().findMember(getPropertyFilePath()));
	}

	private void addChanges(TextChange textChange, NLSSubstitution[] substitutions, String prefix) throws CoreException {
		PropertyFileDocumentModell modell= new PropertyFileDocumentModell(new Document(textChange.getCurrentContent()));
		KeyValuePair[] keyValuePairs= substitutionsToKeyValue(substitutions, prefix);

		InsertEdit[] inserts= modell.insert(keyValuePairs);
		for (int i= 0; i < inserts.length; i++) {
			String message= NLSMessages.getFormattedString("NLSRefactoring.add_entry", keyValuePairs[i].fKey); //$NON-NLS-1$
			TextChangeCompatibility.addTextEdit(textChange, message, inserts[i]);
		}
	}

	private KeyValuePair[] substitutionsToKeyValue(NLSSubstitution[] substitutions, String prefix) {
		List subs= new ArrayList(substitutions.length);
		for (int i= 0; i < substitutions.length; i++) {
			NLSSubstitution substitution= substitutions[i];
			if (substitution.task == NLSSubstitution.TRANSLATE) {
				subs.add(new KeyValuePair(prefix + substitution.key, substitution.value.getValue()));
			}
		}

		return (KeyValuePair[])subs.toArray(new KeyValuePair[subs.size()]);
	}

	private String createPropertyFileSource() throws JavaModelException {
		StringBuffer sb= new StringBuffer();
		sb.append(getOldPropertyFileSource());
		if (needsLineDelimiter(sb)) {
			sb.append(fgLineDelimiter);
		}

		NLSSubstitution[] subs= fNlsHolder.getSubstitutions();
		for (int i= 0; i < subs.length; i++) {
			if (subs[i].task == NLSSubstitution.TRANSLATE) {
				if (subs[i].putToPropertyFile) {
					sb.append(createEntry(subs[i].value, subs[i].getKeyWithPrefix(fSubstitutionPrefix)).toString());
				}
			}
		}
		return sb.toString();
	}

	//heuristic only
	private static boolean needsLineDelimiter(StringBuffer sb) {
		if (sb.length() == 0)
			return false;
		String s= sb.toString();
		int lastDelimiter= s.lastIndexOf(fgLineDelimiter);
		if (lastDelimiter == -1)
			return true;
		if ("".equals(s.substring(lastDelimiter).trim())) //$NON-NLS-1$
			return false;
		return true;
	}

	private String getOldPropertyFileSource() throws JavaModelException {
		if (!propertyFileExists())
			return ""; //$NON-NLS-1$

		//must read the whole contents - don't want to lose comments etc.
		InputStream is= getPropertyFileInputStream();
		String s= NLSUtil.readString(is);
		return s == null ? "" : s; //$NON-NLS-1$
	}

	private StringBuffer createEntry(NLSElement element, String key) {
		StringBuffer sb= new StringBuffer();
		sb.append(key).append('=').append(convertToPropertyValue(element.getValue())).append(fgLineDelimiter);
		return sb;
	}

	/*
	 * see 21.6.7 of langspec-1.0
	 * 
	 * @see java.util.Properties#load(InputStream)
	 */
	private static String convertToPropertyValue(String v) {
		int firstNonWhiteSpace= findFirstNonWhiteSpace(v);
		if (firstNonWhiteSpace == 0)
			return v;
		return escapeEachChar(v.substring(0, firstNonWhiteSpace), '\\') + escapeCommentChars(v.substring(firstNonWhiteSpace));
	}

	private static StringBuffer escapeCommentChars(String string) {
		StringBuffer sb= new StringBuffer(string.length() + 5);
		for (int i= 0; i < string.length(); i++) {
			char c= string.charAt(i);
			switch (c) {
				case '!' :
					sb.append("\\!"); //$NON-NLS-1$
				case '#' :
					sb.append("\\#"); //$NON-NLS-1$
				default :
					sb.append(c);
			}
		}
		return sb;
	}

	private static String escapeEachChar(String s, char escapeChar) {
		char[] chars= new char[s.length() * 2];

		for (int i= 0; i < s.length(); i++) {
			chars[2 * i]= escapeChar;
			chars[2 * i + 1]= s.charAt(i);
		}
		return new String(chars);
	}

	/**
	 * returns the length if only whitespaces
	 */
	private static int findFirstNonWhiteSpace(String s) {
		for (int i= 0; i < s.length(); i++) {
			if (!Character.isWhitespace(s.charAt(i)))
				return i;
		}
		return s.length();
	}

	private static boolean typeNameExistsInPackage(IPackageFragment pack, String name) throws JavaModelException {
		return Checks.findTypeInPackage(pack, name) != null;
	}

	public String getAccessorClassName() {
		return fAccessorClassName;
	}

	public void setAccessorClassName(String name) {
		Assert.isNotNull(name);
		fAccessorClassName= name;
	}

	private String getAccessorCUName() {
		return fAccessorClassName + ".java"; //$NON-NLS-1$
	}

	private IPath getAccessorCUPath() {
		IPath res= fAccessorPackage.getPath().append(getAccessorCUName());
		return res;
	}

	public void setAccessorPackage(IPackageFragment packageFragment) {
		Assert.isNotNull(packageFragment);
		fAccessorPackage= packageFragment;
	}

	public void setSubstitutionPrefix(String string) {
		fSubstitutionPrefix= string;
	}

	public NLSSubstitution[] getSubstitutions() {
		return fNlsHolder.getSubstitutions();
	}

	public NLSLine[] getNLSLines() {
		return fNlsHolder.getLines();
	}

	public String getPrefixHint() {
		String cuName= fCu.getElementName();
		if (cuName.endsWith(".java")) //$NON-NLS-1$
			return cuName.substring(0, cuName.length() - ".java".length()) + '.'; //$NON-NLS-1$
		return ""; //$NON-NLS-1$
	}

	public NLSHint getNlsHint() {
		if (fNlsHint == null) {
			fNlsHint= new NLSHint(fNlsHolder.getLines(), fCu);
		}
		return fNlsHint;
	}

	public static String getDefaultPropertiesFilename() {
		return DEFAULT_PROPERTY_FILENAME + PROPERTY_FILE_EXT;
	}
}