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

package org.eclipse.jdt.internal.corext.refactoring.typeconstraints2;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;

import org.eclipse.jdt.internal.corext.refactoring.typeconstraints.types.TType;

/**
 * A VariableVariable is a ConstraintVariable which stands for
 * the type of a variable, namely a field or a local variable
 * Use {@link ParameterTypeVariable2} for method parameters).
 */
public class VariableVariable2 extends TypeConstraintVariable2 implements IDeclaredConstraintVariable {

	private String fVariableBindingKey;
	private ICompilationUnit fCompilationUnit;
	
	public VariableVariable2(TType type, IVariableBinding variableBinding) {
		super(type);
		fVariableBindingKey= variableBinding.getKey();
	}

	public void setCompilationUnit(ICompilationUnit cu) {
		fCompilationUnit= cu;
	}
	
	public ICompilationUnit getCompilationUnit() {
		return fCompilationUnit;
	}

	public String getVariableBindingKey() {
		return fVariableBindingKey;
	}

	/*
	 * @see java.lang.Object#hashCode()
	 */
	public int hashCode() {
		return fVariableBindingKey.hashCode();
	}

	/*
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object other) {
		if (this == other)
			return true;
		if (other.getClass() != VariableVariable2.class)
			return false;
		
		return fVariableBindingKey.equals(((VariableVariable2) other).getVariableBindingKey());
	}

}
