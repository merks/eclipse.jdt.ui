/*******************************************************************************
 * Copyright (c) 2000, 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.jdt.internal.corext.refactoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;

import org.eclipse.ltk.core.refactoring.RefactoringStatus;

import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.ArrayType;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclarationStatement;
import org.eclipse.jdt.core.dom.TypeParameter;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;

import org.eclipse.jdt.internal.corext.Assert;
import org.eclipse.jdt.internal.corext.dom.ASTFlattener;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.dom.SelectionAnalyzer;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringFileBuffers;
import org.eclipse.jdt.internal.corext.util.AllTypesCache;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.corext.util.TypeInfo;

public class TypeContextChecker {
	//TODO: generalize to also work in a packageFragment (no base cu/root)

	private static class MethodTypesChecker {

		private static final String METHOD_NAME= "__$$__"; //$NON-NLS-1$

		private final IMethod fMethod;
		private final StubTypeContext fStubTypeContext;
		private final List/*<ParameterInfo>*/ fParameterInfos;
		private final ReturnTypeInfo fReturnTypeInfo;

		public MethodTypesChecker(IMethod method, StubTypeContext stubTypeContext, List/*<ParameterInfo>*/ parameterInfos, ReturnTypeInfo returnTypeInfo) {
			fMethod= method;
			fStubTypeContext= stubTypeContext;
			fParameterInfos= parameterInfos;
			fReturnTypeInfo= returnTypeInfo;
		}
		
		public RefactoringStatus[] checkAndResolveMethodTypes() throws CoreException {
			RefactoringStatus[] results= checkSyntax();
			for (int i= 0; i < results.length; i++)
				if (results[i] != null && results[i].hasFatalError())
					return results;
			
			int parameterCount= fParameterInfos.size();
			String[] types= new String[parameterCount + 1];
			for (int i= 0; i < parameterCount; i++)
				types[i]= ParameterInfo.stripEllipsis(((ParameterInfo) fParameterInfos.get(i)).getNewTypeName());
			types[parameterCount]= fReturnTypeInfo.getNewTypeName();
			RefactoringStatus[] semanticsResults= new RefactoringStatus[parameterCount + 1];
			ITypeBinding[] typeBindings= resolveBindings(types, semanticsResults, true);
			
			boolean needsSecondPass= false;
			for (int i= 0; i < types.length; i++)
				if (typeBindings[i] == null || ! semanticsResults[i].isOK())
					needsSecondPass= true;
			
			RefactoringStatus[] semanticsResults2= new RefactoringStatus[parameterCount + 1];
			if (needsSecondPass)
				typeBindings= resolveBindings(types, semanticsResults2, false);
			
			for (int i= 0; i < fParameterInfos.size(); i++) {
				((ParameterInfo) fParameterInfos.get(i)).setNewTypeBinding(typeBindings[i]);
				if (typeBindings[i] == null || (needsSecondPass && ! semanticsResults2[i].isOK())) {
					if (results[i] == null)
						results[i]= semanticsResults2[i];
					else
						results[i].merge(semanticsResults2[i]);
				}
			}
			fReturnTypeInfo.setNewTypeBinding(typeBindings[fParameterInfos.size()]);
			if (typeBindings[parameterCount] == null || (needsSecondPass && ! semanticsResults2[parameterCount].isOK())) {
				if (results[parameterCount] == null)
					results[parameterCount]= semanticsResults2[parameterCount];
				else
					results[parameterCount].merge(semanticsResults2[parameterCount]);
			}
			
			return results;
		}

		public RefactoringStatus[] checkSyntax() {
			int parameterCount= fParameterInfos.size();
			RefactoringStatus[] results= new RefactoringStatus[parameterCount + 1];
			results[parameterCount]= checkReturnTypeSyntax();
			for (int i= 0; i < parameterCount; i++) {
				ParameterInfo info= (ParameterInfo) fParameterInfos.get(i);
				results[i]= checkParameterTypeSyntax(info);
			}
			return results;
		}
		
		private RefactoringStatus checkParameterTypeSyntax(ParameterInfo info) {
			if (! info.isAdded() && ! info.isTypeNameChanged())
				return null;

			String newTypeName= ParameterInfo.stripEllipsis(info.getNewTypeName().trim()).trim();
			
			if ("".equals(newTypeName.trim())){ //$NON-NLS-1$
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.parameter_type", new String[]{info.getNewName()}); //$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
			
			if (info.isNewVarargs() && ! JavaModelUtil.is50OrHigher(fMethod.getJavaProject())) {
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.no_vararg_below_50", new String[]{info.getNewName()}); //$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
			
			List problemsCollector= new ArrayList(0);
			Type parsedType= parseType(newTypeName, problemsCollector);
			boolean valid= parsedType != null;
			if (valid && parsedType instanceof PrimitiveType)
				valid= ! PrimitiveType.VOID.equals(((PrimitiveType) parsedType).getPrimitiveTypeCode());
			if (! valid) {
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.invalid_type_name", new String[]{newTypeName}); //$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
			if (problemsCollector.size() == 0)
				return null;
			
			RefactoringStatus result= new RefactoringStatus();
			for (Iterator iter= problemsCollector.iterator(); iter.hasNext();) {
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.invalid_type_syntax", new String[]{newTypeName, (String) iter.next()}); //$NON-NLS-1$
				result.addError(msg);
			}
			return result;
		}
		
		private RefactoringStatus checkReturnTypeSyntax() {
			String newTypeName= fReturnTypeInfo.getNewTypeName();
			if ("".equals(newTypeName.trim())) { //$NON-NLS-1$
				String msg= RefactoringCoreMessages.getString("ChangeSignatureRefactoring.return_type_not_empty"); //$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
			List problemsCollector= new ArrayList(0);
			Type parsedType= parseType(newTypeName, problemsCollector);
			if (parsedType == null) {
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.invalid_return_type", new String[]{newTypeName}); //$NON-NLS-1$
				return RefactoringStatus.createFatalErrorStatus(msg);
			}
			if (problemsCollector.size() == 0)
				return null;
			
			RefactoringStatus result= new RefactoringStatus();
			for (Iterator iter= problemsCollector.iterator(); iter.hasNext();) {
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.invalid_return_type_syntax", new String[]{newTypeName, (String) iter.next()}); //$NON-NLS-1$
				result.addError(msg);
			}
			return result;
		}

		private Type parseType(String typeString, List/*<IProblem>*/ problemsCollector) {
			if ("".equals(typeString.trim())) //speed up for a common case //$NON-NLS-1$
				return null;
			if (! typeString.trim().equals(typeString))
				return null;

			StringBuffer cuBuff= new StringBuffer();
			cuBuff.append("interface A{"); //$NON-NLS-1$
			int offset= cuBuff.length();
			cuBuff.append(typeString).append(" m();}"); //$NON-NLS-1$

			ASTParser p= ASTParser.newParser(AST.JLS3);
			p.setSource(cuBuff.toString().toCharArray());
			p.setProject(fMethod.getJavaProject());
			CompilationUnit cu= (CompilationUnit) p.createAST(null);
			Selection selection= Selection.createFromStartLength(offset, typeString.length());
			SelectionAnalyzer analyzer= new SelectionAnalyzer(selection, false);
			cu.accept(analyzer);
			ASTNode selected= analyzer.getFirstSelectedNode();
			if (!(selected instanceof Type))
				return null;
			Type type= (Type)selected;
			if (isVoidArrayType(type))
				return null;
			IProblem[] problems= ASTNodes.getProblems(type, ASTNodes.NODE_ONLY, ASTNodes.PROBLEMS);
			if (problems.length > 0) {
				for (int i= 0; i < problems.length; i++)
					problemsCollector.add(problems[i].getMessage());
			}
			
			String typeNodeRange= cuBuff.substring(type.getStartPosition(), ASTNodes.getExclusiveEnd(type));
			if (typeString.equals(typeNodeRange))
				return type;
			else
				return null;
		}

		private static boolean isVoidArrayType(Type type){
			if (! type.isArrayType())
				return false;
			
			ArrayType arrayType= (ArrayType)type;
			if (! arrayType.getComponentType().isPrimitiveType())
				return false;
			PrimitiveType primitiveType= (PrimitiveType)arrayType.getComponentType();
			return (primitiveType.getPrimitiveTypeCode() == PrimitiveType.VOID);
		}
		
		private ITypeBinding[] resolveBindings(String[] types, RefactoringStatus[] results, boolean firstPass) throws CoreException {
			//TODO: split types into parameterTypes and returnType
			int parameterCount= types.length - 1;
			ITypeBinding[] typeBindings= new ITypeBinding[types.length];
			StringBuffer cuString= new StringBuffer();
			cuString.append(fStubTypeContext.getBeforeString());
			
			if (Flags.isStatic(fMethod.getFlags()))
				cuString.append("static "); //$NON-NLS-1$

			ITypeParameter[] methodTypeParameters= fMethod.getTypeParameters();
			if (methodTypeParameters.length != 0) {
				cuString.append('<');
				for (int i= 0; i < methodTypeParameters.length; i++) {
					ITypeParameter typeParameter= methodTypeParameters[i];
					if (i > 0)
						cuString.append(',');
					cuString.append(typeParameter.getElementName());
				}
				cuString.append("> "); //$NON-NLS-1$
			}
			
			cuString.append(types[parameterCount]).append(' ');
			int offsetBeforeMethodName= cuString.length();
			cuString.append(METHOD_NAME).append('('); //$NON-NLS-1$
			for (int i= 0; i < parameterCount; i++) {
				if (i > 0)
					cuString.append(',');
				cuString.append(types[i]).append(" p").append(i); //$NON-NLS-1$
			}
			cuString.append(");"); //$NON-NLS-1$

			cuString.append(fStubTypeContext.getAfterString());
			// need a working copy to tell the parser where to resolve (package visible) types
			ICompilationUnit wc= fMethod.getCompilationUnit().getWorkingCopy(new WorkingCopyOwner() {/*subclass*/}, null, new NullProgressMonitor());
			try {
				wc.getBuffer().setContents(cuString.toString());
				CompilationUnit compilationUnit= new RefactoringASTParser(AST.JLS3).parse(wc, true);
				ASTNode method= NodeFinder.perform(compilationUnit, offsetBeforeMethodName, METHOD_NAME.length()).getParent();
				Type[] typeNodes= new Type[types.length];
				if (method instanceof MethodDeclaration) {
					MethodDeclaration methodDeclaration= (MethodDeclaration) method;
					typeNodes[parameterCount]= methodDeclaration.getReturnType2();
					List/*<SingleVariableDeclaration>*/ parameters= methodDeclaration.parameters();
					for (int i= 0; i < parameterCount; i++)
						typeNodes[i]= ((SingleVariableDeclaration) parameters.get(i)).getType();

				} else if (method instanceof AnnotationTypeMemberDeclaration) {
					typeNodes[0]= ((AnnotationTypeMemberDeclaration) method).getType();
				}

				for (int i= 0; i < types.length; i++) {
					Type type= typeNodes[i];
					if (type == null) {
						results[i]= RefactoringStatus.createErrorStatus("Could not resolve type '" + types[i] + "'.");
						continue;
					}
					results[i]= new RefactoringStatus();
					IProblem[] problems= ASTNodes.getProblems(type, ASTNodes.NODE_ONLY, ASTNodes.PROBLEMS);
					if (problems.length > 0) {
						for (int p= 0; p < problems.length; p++)
							results[i].addError(problems[p].getMessage());
					}
					typeBindings[i]= type.resolveBinding();
					if (typeBindings[i] != null && typeBindings[i].isGenericType() && ! typeBindings[i].isRawType() && ! typeBindings[i].isParameterizedType()) {
						//TODO: workaround for bug 81101
						typeBindings[i]= null;
					}
					if (firstPass && typeBindings[i] == null)
						types[i]= qualifyTypes(types[i], type, results[i]);
				}
				return typeBindings;
			} finally {
				wc.discardWorkingCopy();
			}
		}

		private String qualifyTypes(String string, Type type, final RefactoringStatus result) throws CoreException {
			class NestedException extends RuntimeException {
				private static final long serialVersionUID= 1L;
				NestedException(CoreException e) {
					super(e);
				}
			}
			ASTFlattener flattener= new ASTFlattener() {
				public boolean visit(SimpleName node) {
					appendResolved(node.getIdentifier());
					return false;
				}
				public boolean visit(QualifiedName node) {
					appendResolved(node.getFullyQualifiedName());
					return false;
				}
				public boolean visit(QualifiedType node) {
					appendResolved(ASTNodes.asString(node));
					return false;
				}
				private void appendResolved(String typeName) {
					String resolvedType;
					try {
						resolvedType= resolveType(typeName, result, fMethod.getDeclaringType(), null);
					} catch (CoreException e) {
						throw new NestedException(e);
					}
					this.fBuffer.append(resolvedType);
				}
			};
			try {
				type.accept(flattener);
			} catch (NestedException e) {
				throw ((CoreException) e.getCause());
			}
			return flattener.getResult();
		}

		private static String resolveType(String elementTypeName, RefactoringStatus status, IType declaringType, IProgressMonitor pm) throws CoreException {
			String[][] fqns= declaringType.resolveType(elementTypeName);
			if (fqns != null) {
				if (fqns.length == 1) {
					return JavaModelUtil.concatenateName(fqns[0][0], fqns[0][1]);
				} else if (fqns.length > 1){
					String[] keys= {elementTypeName, String.valueOf(fqns.length)};
					String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.ambiguous", keys); //$NON-NLS-1$
					status.addError(msg);
					return elementTypeName;
				}
			}
			
			List typeRefsFound= findTypeInfos(elementTypeName, declaringType, pm);
			if (typeRefsFound.size() == 0){
				String[] keys= {elementTypeName};
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.not_unique", keys); //$NON-NLS-1$
				status.addError(msg);
				return elementTypeName;
			} else if (typeRefsFound.size() == 1){
				TypeInfo typeInfo= (TypeInfo) typeRefsFound.get(0);
				return typeInfo.getFullyQualifiedName();
			} else {
				Assert.isTrue(typeRefsFound.size() > 1);
				String[] keys= {elementTypeName, String.valueOf(typeRefsFound.size())};
				String msg= RefactoringCoreMessages.getFormattedString("ChangeSignatureRefactoring.ambiguous", keys); //$NON-NLS-1$
				status.addError(msg);
				return elementTypeName;
			}
		}

		private static List findTypeInfos(String typeName, IType contextType, IProgressMonitor pm) throws JavaModelException {
			IJavaSearchScope scope= SearchEngine.createJavaSearchScope(new IJavaProject[]{contextType.getJavaProject()}, true);
			IPackageFragment currPackage= contextType.getPackageFragment();
			TypeInfo[] infos= AllTypesCache.getTypesForName(typeName, scope, pm);
			List typeRefsFound= new ArrayList();
			for (int i= 0; i < infos.length; i++) {
				TypeInfo curr= infos[i];
				IType type= curr.resolveType(scope);
				if (type != null && JavaModelUtil.isVisible(type, currPackage)) {
					typeRefsFound.add(curr);
				}
			}
			return typeRefsFound;
		}

	}
	
	public static RefactoringStatus[] checkAndResolveMethodTypes(IMethod method, StubTypeContext stubTypeContext, List parameterInfos, ReturnTypeInfo returnTypeInfo) throws CoreException {
		MethodTypesChecker checker= new MethodTypesChecker(method, stubTypeContext, parameterInfos, returnTypeInfo);
		return checker.checkAndResolveMethodTypes();
	}

	public static RefactoringStatus[] checkMethodTypesSyntax(IMethod method, StubTypeContext stubTypeContext, List parameterInfos, ReturnTypeInfo returnTypeInfo) throws CoreException {
		MethodTypesChecker checker= new MethodTypesChecker(method, stubTypeContext, parameterInfos, returnTypeInfo);
		return checker.checkSyntax();
	}
	
	public static StubTypeContext createStubTypeContext(ICompilationUnit cu, CompilationUnit root, int focalPosition) throws CoreException {
		IDocument document= RefactoringFileBuffers.acquire(cu).getDocument();
		try {
			StringBuffer bufBefore= new StringBuffer();
			StringBuffer bufAfter= new StringBuffer();
			
			int introEnd= 0;
			PackageDeclaration pack= root.getPackage();
			if (pack != null)
				introEnd= pack.getStartPosition() + pack.getLength();
			List imports= root.imports();
			if (imports.size() > 0) {
				ImportDeclaration lastImport= (ImportDeclaration) imports.get(imports.size() - 1);
				introEnd= lastImport.getStartPosition() + lastImport.getLength();
			}
			try {
				bufBefore.append(document.get(0, introEnd));
			} catch (BadLocationException e) {
				throw new RuntimeException(e); // doesn't happen
			}
			
			fillWithTypeStubs(bufBefore, bufAfter, focalPosition, root.types());
			bufBefore.append(' ');
			bufAfter.insert(0, ' ');
			return new StubTypeContext(cu, bufBefore.toString(), bufAfter.toString());
			
		} finally {
			RefactoringFileBuffers.release(cu);
		}
	}

	private static void fillWithTypeStubs(StringBuffer bufBefore, StringBuffer bufAfter, int focalPosition, List/*<? extends BodyDeclaration>*/ types) {
		StringBuffer buf;
		for (Iterator iter= types.iterator(); iter.hasNext();) {
			BodyDeclaration bodyDeclaration= (BodyDeclaration) iter.next();
			if (! (bodyDeclaration instanceof AbstractTypeDeclaration)) {
				//account for local classes:
				if (! (bodyDeclaration instanceof MethodDeclaration))
					continue;
				int bodyStart= bodyDeclaration.getStartPosition();
				int bodyEnd= bodyDeclaration.getStartPosition() + bodyDeclaration.getLength();
				if (! (bodyStart < focalPosition && focalPosition < bodyEnd))
					continue;
				MethodDeclaration methodDeclaration= (MethodDeclaration) bodyDeclaration;
				buf= bufBefore;
				appendModifiers(buf, methodDeclaration.modifiers());
				appendTypeParameters(buf, methodDeclaration.typeParameters());
				buf.append(" void "); //$NON-NLS-1$
				buf.append(methodDeclaration.getName().getIdentifier());
				buf.append("(){\n"); //$NON-NLS-1$
				List statements= methodDeclaration.getBody().statements();
				for (Iterator iterator= statements.iterator(); iterator.hasNext();) {
					Statement statement= (Statement) iterator.next();
					if (statement instanceof TypeDeclarationStatement) {
						AbstractTypeDeclaration localType= ((TypeDeclarationStatement) statement).getDeclaration();
						fillWithTypeStubs(bufBefore, bufAfter, focalPosition, Collections.singletonList(localType));
					}
					//TODO: does not work for anonymous inner classes and local classes declared inside a block!
					// Does not propose type parameters of the method
				}
				buf= bufAfter;
				buf.append("}\n"); //$NON-NLS-1$
				continue;
			}
			
			AbstractTypeDeclaration decl= (AbstractTypeDeclaration) bodyDeclaration;
			buf= decl.getStartPosition() < focalPosition ? bufBefore : bufAfter;
			appendModifiers(buf, decl.modifiers());
			
			if (decl instanceof TypeDeclaration) {
				TypeDeclaration type= (TypeDeclaration) decl;
				buf.append(type.isInterface() ? "interface " : "class "); //$NON-NLS-1$//$NON-NLS-2$
				buf.append(type.getName().getIdentifier());
				appendTypeParameters(buf, type.typeParameters());
				if (type.getSuperclassType() != null) {
					buf.append(" extends "); //$NON-NLS-1$
					buf.append(ASTNodes.asString(type.getSuperclassType()));
				}
				List superInterfaces= type.superInterfaceTypes();
				appendSuperInterfaces(buf, superInterfaces);
				
			} else if (decl instanceof AnnotationTypeDeclaration) {
				AnnotationTypeDeclaration annotation= (AnnotationTypeDeclaration) decl;
				buf.append("@interface "); //$NON-NLS-1$
				buf.append(annotation.getName().getIdentifier());
				
			} else if (decl instanceof EnumDeclaration) {
				EnumDeclaration enumDecl= (EnumDeclaration) decl;
				buf.append("enum "); //$NON-NLS-1$
				buf.append(enumDecl.getName().getIdentifier());
				List superInterfaces= enumDecl.superInterfaceTypes();
				appendSuperInterfaces(buf, superInterfaces);
			}
			
			buf.append("{\n"); //$NON-NLS-1$
			if (decl instanceof EnumDeclaration)
				buf.append(";\n"); //$NON-NLS-1$
			fillWithTypeStubs(bufBefore, bufAfter, focalPosition, decl.bodyDeclarations());
			buf= decl.getStartPosition() + decl.getLength() < focalPosition ? bufBefore : bufAfter;
			buf.append("}\n"); //$NON-NLS-1$
		}
	}

	private static void appendTypeParameters(StringBuffer buf, List typeParameters) {
		int typeParametersCount= typeParameters.size();
		if (typeParametersCount > 0) {
			buf.append('<');
			for (int i= 0; i < typeParametersCount; i++) {
				TypeParameter typeParameter= (TypeParameter) typeParameters.get(i);
				buf.append(ASTNodes.asString(typeParameter));
				if (i < typeParametersCount - 1)
					buf.append(',');
			}
		}
	}

	private static void appendModifiers(StringBuffer buf, List modifiers) {
		for (Iterator iterator= modifiers.iterator(); iterator.hasNext();) {
			IExtendedModifier extendedModifier= (IExtendedModifier) iterator.next();
			if (extendedModifier.isModifier()) {
				Modifier modifier= (Modifier) extendedModifier;
				buf.append(modifier.getKeyword().toString()).append(' ');
			}
		}
	}

	private static void appendSuperInterfaces(StringBuffer buf, List superInterfaces) {
		int superInterfaceCount= superInterfaces.size();
		if (superInterfaceCount > 0) {
			buf.append(" implements "); //$NON-NLS-1$
			for (int i= 0; i < superInterfaceCount; i++) {
				Type superInterface= (Type) superInterfaces.get(i);
				buf.append(ASTNodes.asString(superInterface));
				if (i < superInterfaceCount - 1)
					buf.append(',');
			}
		}
	}


}
