/*******************************************************************************
 * Copyright (c) 2023, 2024 IBM Corporation and others.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     Gayan Perera - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.fix;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.CoreException;

import org.eclipse.jface.text.BadLocationException;

import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;

import org.eclipse.jdt.internal.core.manipulation.dom.ASTResolving;
import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.refactoring.nls.NLSElement;
import org.eclipse.jdt.internal.corext.refactoring.nls.NLSLine;
import org.eclipse.jdt.internal.corext.refactoring.nls.NLSScanner;
import org.eclipse.jdt.internal.corext.refactoring.structure.CompilationUnitRewrite;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;

import org.eclipse.jdt.internal.ui.text.correction.CorrectionMessages;

public class ConvertToMessageFormatFixCore extends CompilationUnitRewriteOperationsFixCore {

	/**
	 * Should match the last NLS comment before end of the line
	 */
	static final Pattern comment= Pattern.compile("([ ]*\\/\\/\\$NON-NLS-[0-9]\\$) *$"); //$NON-NLS-1$

	public ConvertToMessageFormatFixCore(String name, CompilationUnit compilationUnit, CompilationUnitRewriteOperation operation) {
		super(name, compilationUnit, operation);
	}

	public static ConvertToMessageFormatFixCore createConvertToMessageFormatFix(CompilationUnit compilationUnit, ASTNode node) {
		BodyDeclaration parentDecl= ASTResolving.findParentBodyDeclaration(node);
		if (!(parentDecl instanceof MethodDeclaration) && !(parentDecl instanceof Initializer))
			return null;

		AST ast= node.getAST();
		ITypeBinding stringBinding= ast.resolveWellKnownType("java.lang.String"); //$NON-NLS-1$

		if (node instanceof Expression && !(node instanceof InfixExpression)) {
			node= node.getParent();
		}
		if (node instanceof VariableDeclarationFragment) {
			node= ((VariableDeclarationFragment) node).getInitializer();
		} else if (node instanceof Assignment) {
			node= ((Assignment) node).getRightHandSide();
		}

		InfixExpression oldInfixExpression= null;
		while (node instanceof InfixExpression) {
			InfixExpression curr= (InfixExpression) node;
			if (curr.resolveTypeBinding() == stringBinding && curr.getOperator() == InfixExpression.Operator.PLUS) {
				oldInfixExpression= curr; // is a infix expression we can use
			} else {
				break;
			}
			node= node.getParent();
		}
		if (oldInfixExpression == null) {
			return null;
		}

		boolean is50OrHigher= JavaModelUtil.is50OrHigher(compilationUnit.getTypeRoot().getJavaProject());
		// collect operands
		List<Expression> operands= new ArrayList<>();
		collectInfixPlusOperands(oldInfixExpression, operands);

		boolean foundNoneLiteralOperand= false;
		boolean seenTag= false;
		boolean seenNoTag= false;
		// we need to loop through all to exclude any null binding scenarios.
		for (Expression operand : operands) {
			if (!(operand instanceof StringLiteral)) {
				if (!is50OrHigher) {
					ITypeBinding binding= operand.resolveTypeBinding();
					if (binding == null) {
						return null;
					}
				}
				foundNoneLiteralOperand= true;
			} else {
				// ensure either all string literals are nls-tagged or none are
				ICompilationUnit cu= (ICompilationUnit)compilationUnit.getJavaElement();
				NLSLine nlsLine= scanCurrentLine(cu, operand);
				if (nlsLine != null) {
					for (NLSElement element : nlsLine.getElements()) {
						if (element.getPosition().getOffset() == compilationUnit.getColumnNumber(operand.getStartPosition())) {
							if (element.hasTag()) {
								if (seenNoTag) {
									return null;
								}
								seenTag= true;
							} else {
								if (seenTag) {
									return null;
								}
								seenNoTag= true;
							}
							break;
						}
					}
				}
			}
		}

		if (!foundNoneLiteralOperand) {
			return null;
		}

		return new ConvertToMessageFormatFixCore(CorrectionMessages.QuickAssistProcessor_convert_to_message_format, compilationUnit,
				new ConvertToMessageFormatProposalOperation(oldInfixExpression));
	}

	private static NLSLine scanCurrentLine(ICompilationUnit cu, Expression exp) {
		CompilationUnit cUnit= (CompilationUnit)exp.getRoot();
		int startLine= cUnit.getLineNumber(exp.getStartPosition());
		int startLinePos= cUnit.getPosition(startLine, 0);
		int endOfLine= cUnit.getPosition(startLine + 1, 0);
		NLSLine[] lines;
		try {
			lines= NLSScanner.scan(cu.getBuffer().getText(startLinePos, endOfLine - startLinePos));
			if (lines.length > 0) {
				return lines[0];
			}
		} catch (IndexOutOfBoundsException | JavaModelException | InvalidInputException | BadLocationException e) {
			// fall-through
		}
		return null;
	}

	private static String indentOf(ICompilationUnit cu, Expression exp) {
		CompilationUnit cUnit= (CompilationUnit)exp.getRoot();
		int startLine= cUnit.getLineNumber(exp.getStartPosition());
		int startLinePos= cUnit.getPosition(startLine, 0);
		int endOfLine= cUnit.getPosition(startLine + 1, 0);
		String indent= ""; //$NON-NLS-1$
		IBuffer buffer;
		try {
			buffer= cu.getBuffer();
			String line= buffer.getText(startLinePos, endOfLine - startLinePos);
			for (int i= 0; i < line.length(); ++i) {
				char ch= line.charAt(i);
				if (Character.isSpaceChar(ch)) {
					indent+= ch;
				} else {
					break;
				}
			}
		} catch (JavaModelException e) {
			// ignore
		}
		return indent;
	}

	private static void collectInfixPlusOperands(Expression expression, List<Expression> collector) {
		if (expression instanceof InfixExpression && ((InfixExpression) expression).getOperator() == InfixExpression.Operator.PLUS) {
			InfixExpression infixExpression= (InfixExpression) expression;

			collectInfixPlusOperands(infixExpression.getLeftOperand(), collector);
			collectInfixPlusOperands(infixExpression.getRightOperand(), collector);
			List<Expression> extendedOperands= infixExpression.extendedOperands();
			for (Expression expression2 : extendedOperands) {
				collectInfixPlusOperands(expression2, collector);
			}

		} else {
			collector.add(expression);
		}
	}

	private static class ConvertToMessageFormatProposalOperation extends CompilationUnitRewriteOperation {
		private InfixExpression infixExpression;


		public ConvertToMessageFormatProposalOperation(InfixExpression infixExpression) {
			this.infixExpression= infixExpression;
		}

		@Override
		public void rewriteAST(CompilationUnitRewrite cuRewrite, LinkedProposalModelCore linkedModel) throws CoreException {
			final List<String> fLiterals= new ArrayList<>();
			String fIndent= ""; //$NON-NLS-1$
			ICompilationUnit cu= cuRewrite.getCu();
			boolean is50OrHigher= JavaModelUtil.is50OrHigher(cu.getJavaProject());
			boolean is15OrHigher= JavaModelUtil.is15OrHigher(cu.getJavaProject());
			AST fAst= cuRewrite.getAST();

			ASTRewrite rewrite= cuRewrite.getASTRewrite();
			CompilationUnit root= cuRewrite.getRoot();
			String cuContents= cuRewrite.getCu().getBuffer().getContents();

			ImportRewrite importRewrite= cuRewrite.getImportRewrite();
			ContextSensitiveImportRewriteContext importContext= new ContextSensitiveImportRewriteContext(root, infixExpression.getStartPosition(), importRewrite);

			// collect operands
			List<Expression> operands= new ArrayList<>();
			collectInfixPlusOperands(infixExpression, operands);

			List<String> formatArguments= new ArrayList<>();
			StringBuilder formatString= new StringBuilder();
			int i= 0;
			int tagsCount= 0;
			boolean firstStringLiteral= true;
			for (Expression operand : operands) {
				if (operand instanceof StringLiteral) {
					if (firstStringLiteral) {
						fIndent= indentOf(cu, operand);
						firstStringLiteral= false;
					}
					NLSLine nlsLine= scanCurrentLine(cu, operand);
					if (nlsLine != null) {
						for (NLSElement element : nlsLine.getElements()) {
							if (element.getPosition().getOffset() == root.getColumnNumber(operand.getStartPosition())) {
								if (element.hasTag()) {
									++tagsCount;
								}
							}
						}
					}
					String value= ((StringLiteral) operand).getEscapedValue();
					value= value.replace("'", "''"); //$NON-NLS-1$ //$NON-NLS-2$
					value= value.replace("{", "'{'"); //$NON-NLS-1$ //$NON-NLS-2$
					value= value.replace("}", "'}'"); //$NON-NLS-1$ //$NON-NLS-2$
					value= value.replace("'{''}'", "'{}'"); //$NON-NLS-1$ //$NON-NLS-2$
					fLiterals.add(value);
					value= value.substring(1, value.length() - 1);
					formatString.append(value);
				} else {
					fLiterals.add("\"{" + Integer.toString(i) + "}\""); //$NON-NLS-1$ //$NON-NLS-2$
					formatString.append("{").append(i).append("}"); //$NON-NLS-1$ //$NON-NLS-2$

					String argument;
					if (is50OrHigher) {
						int origStart= root.getExtendedStartPosition(operand);
						int origLength= root.getExtendedLength(operand);
						argument= cuContents.substring(origStart, origStart + origLength);
					} else {
						ITypeBinding binding= operand.resolveTypeBinding();
						int origStart= root.getExtendedStartPosition(operand);
						int origLength= root.getExtendedLength(operand);
						argument= cuContents.substring(origStart, origStart + origLength);

						if (binding.isPrimitive()) {
							ITypeBinding boxedBinding= Bindings.getBoxedTypeBinding(binding, fAst);
							if (boxedBinding != binding) {
								importRewrite.addImport(boxedBinding, fAst, importContext);
								String cic= "new " + boxedBinding.getName() + "(" + argument + ")"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
								argument= cic;
							}
						}
					}

					formatArguments.add(argument);
					i++;
				}
			}


			importRewrite.addImport("java.text.MessageFormat", importContext); //$NON-NLS-1$

			StringBuilder buffer= new StringBuilder();
			buffer.append("MessageFormat.format("); //$NON-NLS-1$

			if (is15OrHigher) {
				StringBuilder buf= new StringBuilder();

				List<String> parts= new ArrayList<>();
				fLiterals.stream().forEach((t) -> { parts.addAll(StringConcatToTextBlockFixCore.unescapeBlock(t.substring(1, t.length() - 1))); });


				buf.append("\"\"\"\n"); //$NON-NLS-1$
				boolean newLine= false;
				boolean allWhiteSpaceStart= true;
				boolean allEmpty= true;
				for (String part : parts) {
					if (buf.length() > 4) {// the first part has been added after the text block delimiter and newline
						if (!newLine) {
							// no line terminator in this part: merge the line by emitting a line continuation escape
							buf.append("\\").append(System.lineSeparator()); //$NON-NLS-1$
						}
					}
					newLine= part.endsWith(System.lineSeparator());
					allWhiteSpaceStart= allWhiteSpaceStart && (part.isEmpty() || Character.isWhitespace(part.charAt(0)));
					allEmpty= allEmpty && part.isEmpty();
					buf.append(fIndent).append(part);
				}

				if (newLine || allEmpty) {
					buf.append(fIndent);
				} else if (allWhiteSpaceStart) {
					buf.append("\\").append(System.lineSeparator()); //$NON-NLS-1$
					buf.append(fIndent);
				} else {
					// Replace trailing un-escaped quotes with escaped quotes before adding text block end
					int readIndex= buf.length() - 1;
					int count= 0;
					while (readIndex >= 0 && buf.charAt(readIndex) == '"' && count <= 3) {
						--readIndex;
						++count;
					}
					if (readIndex >= 0 && buf.charAt(readIndex) == '\\') {
						--count;
					}
					for (int i1= count; i1 > 0; --i1) {
						buf.deleteCharAt(buf.length() - 1);
					}
					for (int i1= count; i1 > 0; --i1) {
						buf.append("\\\""); //$NON-NLS-1$
					}
				}
				buf.append("\"\"\""); //$NON-NLS-1$
				buffer.append(buf.toString());
			} else {
				buffer.append("\"" + formatString.toString().replaceAll("\"", "\\\"") + "\""); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}

			if (is50OrHigher) {
				for (String formatArgument : formatArguments) {
					buffer.append(", " + formatArgument); //$NON-NLS-1$
				}
			} else {
				buffer.append(", new Object[]{"); //$NON-NLS-1$
				if (formatArguments.size() > 0) {
					buffer.append(formatArguments.get(0));
				}
				for (int i1= 1; i1 < formatArguments.size(); ++i1) {
					buffer.append(", " + formatArguments.get(i1)); //$NON-NLS-1$
				}
				buffer.append("}"); //$NON-NLS-1$
			}
			buffer.append(")"); //$NON-NLS-1$

			if (tagsCount > 1) {
				if (is15OrHigher) {
					Expression lastOperand= operands.get(operands.size() - 1);
					NLSLine nlsLine= scanCurrentLine(cu, lastOperand);
					tagsCount= 0;
					if (nlsLine != null) {
						for (NLSElement element : nlsLine.getElements()) {
							if (element.hasTag()) {
								++tagsCount;
							}
						}
					}
					if (!(lastOperand instanceof StringLiteral) || tagsCount > 1) {
						// if last operand is not a StringLiteral, we have to replace the statement
						// and add a non-NLS marker because we can't add one via expression replacement
						ASTNode statement= ASTNodes.getFirstAncestorOrNull(infixExpression, Statement.class);
						if (statement == null) {
							return;
						}
						CompilationUnit cUnit= (CompilationUnit)infixExpression.getRoot();
						int extendedStart= cUnit.getExtendedStartPosition(statement);
						int extendedLength= cUnit.getExtendedLength(statement);
						String completeStatement= cu.getBuffer().getText(extendedStart, extendedLength);
						if (tagsCount > 1) {
							// remove all non-NLS comments and then replace with just one
							Matcher commentMatcher= comment.matcher(completeStatement);
							while (tagsCount-- > 0) {
								completeStatement= commentMatcher.replaceFirst(""); //$NON-NLS-1$
								commentMatcher= comment.matcher(completeStatement);
							}
							extendedLength= completeStatement.length();
						}
						StringBuilder newBuffer= new StringBuilder();
						newBuffer= newBuffer.append(completeStatement.substring(0, infixExpression.getStartPosition() - extendedStart));
						newBuffer= newBuffer.append(buffer.toString());
						int infixExpressionEnd= infixExpression.getStartPosition() + infixExpression.getLength();
						newBuffer= newBuffer.append(cu.getBuffer().getText(infixExpressionEnd, extendedStart + extendedLength - infixExpressionEnd));
						newBuffer= newBuffer.append(" //$NON-NLS-1$"); //$NON-NLS-1$
						Statement newStatement= (Statement)rewrite.createStringPlaceholder(newBuffer.toString(), statement.getNodeType());
						rewrite.replace(statement, newStatement, null);
					} else {
						MethodInvocation formatInvocation= (MethodInvocation)rewrite.createStringPlaceholder(buffer.toString(), ASTNode.METHOD_INVOCATION);
						rewrite.replace(infixExpression, formatInvocation, null);
					}
				} else {
					ASTNodes.replaceAndRemoveNLSByCount(rewrite, infixExpression, buffer.toString(), tagsCount - 1, null, cuRewrite);
				}
			} else {
				MethodInvocation formatInvocation= (MethodInvocation)rewrite.createStringPlaceholder(buffer.toString(), ASTNode.METHOD_INVOCATION);
				rewrite.replace(infixExpression, formatInvocation, null);
			}
		}
	}
}
