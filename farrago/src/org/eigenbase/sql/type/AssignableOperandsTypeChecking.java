/*
// $Id$
// Package org.eigenbase is a class library of database components.
// Copyright (C) 2004-2004 Disruptive Tech
// Copyright (C) 2004-2004 John V. Sichi.
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package org.eigenbase.sql.type;

import org.eigenbase.sql.*;
import org.eigenbase.reltype.*;

/**
 * AssignableOperandsTypeChecking implements {@link OperandsTypeChecking} by
 * verifying that the type of each argument is assignable to a
 * predefined set of parameter types (under the SQL definition of
 * "assignable").
 *
 * @author John V. Sichi
 * @version $Id$
 */
public class AssignableOperandsTypeChecking extends OperandsTypeChecking
{
    private final RelDataType [] paramTypes;

    /**
     * Instantiates this strategy with a specific set of parameter types.
     *
     * @param paramTypes parameter types for operands; index in
     * this array corresponds to operand number
     */
    public AssignableOperandsTypeChecking(RelDataType [] paramTypes)
    {
        this.paramTypes = paramTypes;
    }

    // implement OperandsTypeChecking
    public int getArgCount()
    {
        return paramTypes.length;
    }
    
    // implement OperandsTypeChecking
    public boolean check(
        SqlCall call,
        SqlValidator validator,
        SqlValidator.Scope scope,
        SqlNode node,
        int ruleOrdinal,
        boolean throwOnFailure)
    {
        return check(validator, scope, call, throwOnFailure);
    }
    
    // implement OperandsTypeChecking
    public boolean check(
        SqlValidator validator,
        SqlValidator.Scope scope,
        SqlCall call,
        boolean throwOnFailure)
    {
        for (int i = 0; i < call.operands.length; ++i) {
            RelDataType argType = validator.deriveType(scope, call.operands[i]);
            if (!SqlTypeUtil.canAssignFrom(paramTypes[i], argType)) {
                if (throwOnFailure) {
                    throw call.newValidationSignatureError(validator, scope);
                } else {
                    return false;
                }
            }
        }
        return true;
    }
    
    // implement OperandsTypeChecking
    public String getAllowedSignatures(SqlOperator op)
    {
        StringBuffer sb = new StringBuffer();
        sb.append(op.name);
        sb.append("(");
        for (int i = 0; i < paramTypes.length; ++i) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("<");
            sb.append(paramTypes[i].getFamily().toString());
            sb.append(">");
        }
        sb.append(")");
        return sb.toString();
    }
}

// End AssignableOperandsTypeChecking.java