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

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;

import org.eclipse.jdt.internal.corext.Assert;

public final class ConstraintOperator2 {
	
	private final String fOperatorString;
	private final int fOperatorCode;

	private static final int CODE_SUBTYPE= 0;
//	private static final int CODE_STRICT_SUBTYPE= 3;
	
	private static final String STRING_SUBTYPE= "<=";//$NON-NLS-1$
//	private static final String STRING_STRICT_SUBTYPE= "<";//$NON-NLS-1$
	private static final Collection fgOperatorStrings= new HashSet(Arrays.asList(new String[] {STRING_SUBTYPE/*, STRING_STRICT_SUBTYPE*/}));

	private static final ConstraintOperator2 fgSubtype= new ConstraintOperator2(STRING_SUBTYPE, CODE_SUBTYPE);
//	private static final ConstraintOperator2 fgStrictSubtype= new ConstraintOperator2(STRING_STRICT_SUBTYPE, CODE_STRICT_SUBTYPE);

	public static ConstraintOperator2 createSubTypeOperator() {
		return fgSubtype;
	}

//	public static ConstraintOperator2 createStrictSubtypeOperator() {
//		return fgStrictSubtype;
//	}
	
	private ConstraintOperator2(String string, int code){
		Assert.isTrue(fgOperatorStrings.contains(string));
		Assert.isTrue(/*code == CODE_STRICT_SUBTYPE ||*/ code == CODE_SUBTYPE);
		fOperatorString= string;
		fOperatorCode= code;
	}
		
	public String getOperatorString(){
		return fOperatorString;
	}
	
	/* (non-Javadoc)
	 * @see java.lang.Object#toString()
	 */
	public String toString() {
		return getOperatorString();
	}
		
	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	public boolean equals(Object obj) {
		if (! (obj instanceof ConstraintOperator2))
			return false;
		ConstraintOperator2 other= (ConstraintOperator2)obj;
		return fOperatorCode == other.fOperatorCode;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	public int hashCode() {
		return fOperatorString.hashCode();
	}

	public final boolean isSubtypeOperator() {
		return fOperatorCode == CODE_SUBTYPE;
	}

//	public final boolean isStrictSubtypeOperator() {
//		return fOperatorCode == CODE_STRICT_SUBTYPE;
//	}
}
