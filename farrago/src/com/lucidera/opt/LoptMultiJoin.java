/*
// $Id$
// Farrago is an extensible data management system.
// Copyright (C) 2005-2005 LucidEra, Inc.
// Copyright (C) 2005-2005 The Eigenbase Project
//
// This program is free software; you can redistribute it and/or modify it
// under the terms of the GNU General Public License as published by the Free
// Software Foundation; either version 2 of the License, or (at your option)
// any later version approved by The Eigenbase Project.
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
package com.lucidera.opt;

import java.util.*;

import org.eigenbase.rel.*;
import org.eigenbase.rel.rules.*;
import org.eigenbase.relopt.*;
import org.eigenbase.reltype.*;
import org.eigenbase.rex.*;
import org.eigenbase.sql.fun.*;
import org.eigenbase.util.Util;

/**
 * LoptMultiJoin is a utility class used to keep track of the join factors
 * that make up a MultiJoinRel.
 * 
 * @author Zelaine Fong
 * @version $Id$
 */
public class LoptMultiJoin
{
    //~ Instance fields --------------------------------------------------------
    
    /**
     * The MultiJoinRel being optimized
     */
    MultiJoinRel multiJoin;
    
    /**
     * Join filters associated with the MultiJoinRel, decomposed into
     * a list
     */
    private List<RexNode> joinFilters;
    
    /**
     * Number of factors into the MultiJoinRel
     */
    private int nJoinFactors;
    
    /**
     * Total number of fields in the MultiJoinRel
     */
    private int nTotalFields;
    
    /**
     * Original inputs into the MultiJoinRel
     */
    private RelNode[] joinFactors;
    
    /**
     * For each join filter, associates a bitmap indicating all factors
     * referenced by the filter
     */
    private Map<RexNode, BitSet> factorsRefByJoinFilter;
    
    /**
     * For each join filter, associates a bitmap indicating all fields
     * referenced by the filter
     */
    private Map<RexNode, BitSet> fieldsRefByJoinFilter;
    
    /**
     * Starting RexInputRef index corresponding to each join factor
     */
    int joinStart[];
    
    /**
     * Number of fields in each join factor
     */
    int nFieldsInJoinFactor[];
    
    /**
     * Bitmap indicating which factors each factor references in join filters
     * that correspond to comparisons
     */
    BitSet[] factorsRefByFactor;
    
    /**
     * Weights of each factor combination
     */
    int[][] factorWeights;

    //~ Constructors -----------------------------------------------------------

    public LoptMultiJoin(MultiJoinRel multiJoin)
    {
    	this.multiJoin = multiJoin;
        joinFactors = multiJoin.getInputs();
        nJoinFactors = joinFactors.length;
        
        joinFilters = new ArrayList<RexNode>();
        RelOptUtil.decompCF(multiJoin.getJoinFilter(), joinFilters);
        
        int start = 0;
        nTotalFields = multiJoin.getRowType().getFields().length;
        joinStart = new int[nJoinFactors];
        nFieldsInJoinFactor = new int[nJoinFactors];
        for (int i = 0; i < nJoinFactors; i++) {
            joinStart[i] = start;
            nFieldsInJoinFactor[i] =
                joinFactors[i].getRowType().getFields().length;
            start += nFieldsInJoinFactor[i];
        }
        
        // determine which join factors each join filter references
        setJoinFilterRefs(nTotalFields);
    }
    
    //~ Methods ----------------------------------------------------------------

    /**
     * @return the MultiJoinRel corresponding to this multijoin
     */
    public MultiJoinRel getMultiJoinRel()
    {
        return multiJoin;
    }
    
    /**
     * @return number of factors in this multijoin
     */
    public int getNumJoinFactors()
    {
    	return nJoinFactors;
    }
    
    /**
     * @param factIdx factor to be returned
     * @return factor corresponding to the factor index passed in
     */
    public RelNode getJoinFactor(int factIdx)
    {
    	return joinFactors[factIdx];
    }
    
    /**
     * @return total number of fields in the multijoin
     */
    public int getNumTotalFields()
    {
    	return nTotalFields;
    }
    
    /**
     * @param factIdx desired factor
     * @return number of fields in the specified factor
     */
    public int getNumFieldsInJoinFactor(int factIdx)
    {
    	return nFieldsInJoinFactor[factIdx];
    }
    
    /**
     * @return all join filters in this multijoin
     */
    public List<RexNode> getJoinFilters()
    {
    	return joinFilters;
    }
    
    /**
     * @param joinFilter filter for which information will be returned
     * @return bitmap corresponding to the factors referenced within the
     * specified join filter
     */
    public BitSet getFactorsRefByJoinFilter(RexNode joinFilter)
    {
    	return factorsRefByJoinFilter.get(joinFilter);
    }
    
    /**
     * @return array of fields contained within the multijoin
     */
    public RelDataTypeField[] getMultiJoinFields()
    {
    	return multiJoin.getRowType().getFields();
    }
    
    /**
     * @param joinFilter the filter for which information will be returned
     * @return bitmap corresponding to the fields referenced by a join filter
     */
    public BitSet getFieldsRefByJoinFilter(RexNode joinFilter)
    {
    	return fieldsRefByJoinFilter.get(joinFilter);
    }
    
    /**
     * @return weights of the different factors relative to one another
     */
    public int[][] getFactorWeights()
    {
    	return factorWeights;
    }
    
    /**
     * @param factIdx factor for which information will be returned
     * @return bitmap corresponding to the factors referenced by the specified
     * factor in the various join filters that correspond to comparisons
     */
    public BitSet getFactorsRefByFactor(int factIdx)
    {
    	return factorsRefByFactor[factIdx];
    }
    
    /**
     * @param factIdx factor for which information will be returned
     * @return starting offset within the multijoin for the specified factor
     */
    public int getJoinStart(int factIdx)
    {
    	return joinStart[factIdx];
    }

    /**
     * Sets bitmaps indicating which factors and fields each join filter
     * references
     * 
     * @param nTotalFields total number of fields referenced by the
     * MultiJoinRel being optimized
     */
    private void setJoinFilterRefs(int nTotalFields)
    {
        fieldsRefByJoinFilter = new HashMap<RexNode, BitSet>();
        factorsRefByJoinFilter = new HashMap<RexNode, BitSet>();
        ListIterator filterIter = joinFilters.listIterator();
        while (filterIter.hasNext()) {
            RexNode joinFilter = (RexNode) filterIter.next();
            // ignore the literal filter; if necessary, we'll add it back
            // later
            if (joinFilter.isAlwaysTrue()) {
                filterIter.remove();
            }
            BitSet fieldRefBitmap = new BitSet(nTotalFields);
            joinFilter.accept(new RelOptUtil.InputFinder(fieldRefBitmap));
            fieldsRefByJoinFilter.put(joinFilter, fieldRefBitmap);
            
            BitSet factorRefBitmap = new BitSet(nJoinFactors);
            setFactorBitmap(factorRefBitmap, fieldRefBitmap);
            factorsRefByJoinFilter.put(joinFilter, factorRefBitmap);
        }
    }
    
    /**
     * Sets the bitmap indicating which factors a filter references based
     * on which fields it references
     * 
     * @param factorRefBitmap bitmap representing factors referenced that will
     * be set by this method
     * @param fieldRefBitmap bitmap reprepsenting fields referenced
     */
    private void setFactorBitmap(BitSet factorRefBitmap, BitSet fieldRefBitmap)
    {
        for (int field = fieldRefBitmap.nextSetBit(0); field >= 0;
            field = fieldRefBitmap.nextSetBit(field + 1))
        {
            int factor = findRef(field);
            factorRefBitmap.set(factor);
        }
    }
    
    /**
     * Determines the join factor corresponding to a RexInputRef
     * 
     * @param rexInputRef rexInputRef index
     * @return index corresponding to join factor
     */
    private int findRef(int rexInputRef)
    {
        for (int i = 0; i < nJoinFactors; i++) {
            if (rexInputRef >= joinStart[i] &&
                rexInputRef < joinStart[i] + nFieldsInJoinFactor[i])
            {
                return i;
            }
        }
        assert(false);
        return 0;
    }
    
    /**
     * Sets weighting for each combination of factors, depending on which
     * join filters reference which factors.  Greater weight is given to
     * equality conditions.  Also, sets bitmaps indicating which factors
     * are referenced by each factor within join filters that are comparisons.
     */
    public void setFactorWeights()
    {
        factorWeights = new int[nJoinFactors][nJoinFactors];
        factorsRefByFactor = new BitSet[nJoinFactors];
        for (int i = 0; i < nJoinFactors; i++) {
            factorsRefByFactor[i] = new BitSet(nJoinFactors);
        }

        for (RexNode joinFilter : joinFilters) {
            BitSet factorRefs = factorsRefByJoinFilter.get(joinFilter);
            // don't give weights to non-comparison expressions
            if (!(joinFilter instanceof RexCall)) {
                continue;
            }
            if (!joinFilter.isA(RexKind.Comparison)) {
                continue;
            }
            
            // OR the factors referenced in this join filter into the
            // bitmaps corresponding to each of the factors; however,
            // exclude the bit corresponding to the factor itself
            for (int factor = factorRefs.nextSetBit(0); factor >= 0;
                factor = factorRefs.nextSetBit(factor + 1))
            {
                factorsRefByFactor[factor].or(factorRefs);
                factorsRefByFactor[factor].clear(factor);
            }
            
            if (factorRefs.cardinality() == 2) {
                int leftFactor = factorRefs.nextSetBit(0);
                int rightFactor = factorRefs.nextSetBit(leftFactor + 1);

                BitSet leftFields = new BitSet(nTotalFields);
                RexNode[] operands = ((RexCall) joinFilter).getOperands();
                operands[0].accept(new RelOptUtil.InputFinder(leftFields));
                BitSet leftBitmap = new BitSet(nJoinFactors);
                setFactorBitmap(leftBitmap, leftFields);
                
                // filter contains only two factor references, one on each
                // side of the operator
                if (leftBitmap.cardinality() == 1) {
                    
                    // give higher weight to equijoins
                    if (((RexCall) joinFilter).getOperator() ==
                            SqlStdOperatorTable.equalsOperator)
                    {
                        setFactorWeight(3, leftFactor, rightFactor);
                    } else {
                        setFactorWeight(2, leftFactor, rightFactor);
                    }
                } else {
                    // cross product of two tables
                    setFactorWeight(1, leftFactor, rightFactor);
                }
            } else {
                // multiple factor references -- set a weight for each 
                // combination of factors referenced within the filter
                for (int outer = factorRefs.nextSetBit(0); outer >= 0;
                    outer = factorRefs.nextSetBit(outer + 1))
                {
                    for (int inner = factorRefs.nextSetBit(0); inner >= 0;
                        inner = factorRefs.nextSetBit(inner + 1))
                    {
                        if (outer != inner) {
                            setFactorWeight(1, outer, inner);
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Sets an individual weight if the new weight is better than the current
     * one
     * 
     * @param weight weight to be set
     * @param leftFactor index of left factor
     * @param rightFactor index of right factor
     */
    private void setFactorWeight(int weight, int leftFactor, int rightFactor)
    {
        if (factorWeights[leftFactor][rightFactor] < weight) {
            factorWeights[leftFactor][rightFactor] = weight;
            factorWeights[rightFactor][leftFactor] = weight;
        }
    }
    
    /**
     * Returns true if a join tree contains all factors required
     * 
     * @param joinTree join tree to be examined
     * @param factorsNeeded bitmap of factors required
     * @return true if join tree contains all required factors
     */
    public boolean hasAllFactors(
    	LoptJoinTree joinTree, BitSet factorsNeeded)
    {
        BitSet childFactors = new BitSet(nJoinFactors);
        getChildFactors(joinTree, childFactors);
        return RelOptUtil.contains(childFactors, factorsNeeded);
    }
    
    /**
     * Sets a bitmap representing all fields corresponding to a RelNode
     * 
     * @param rel relnode for which fields will be set
     * @param fields bitmap containing set bits for each field in a RelNode
     */
    public void setFieldBitmap(LoptJoinTree rel, BitSet fields)
    {
        // iterate through all factors within the RelNode
        BitSet factors = new BitSet(nJoinFactors);
        getChildFactors(rel, factors);
        for (int factor = factors.nextSetBit(0); factor >= 0;
            factor = factors.nextSetBit(factor + 1))
        {
            // set a bit for each field
            for (int i = 0; i < nFieldsInJoinFactor[factor]; i++) {
                fields.set(joinStart[factor] + i);
            }
        }
    }
    
    /**
     * Sets a bitmap indicating all child RelNodes in a join tree
     * 
     * @param joinTree join tree to be examined
     * @param childFactors bitmap to be set
     */
    public void getChildFactors(LoptJoinTree joinTree, BitSet childFactors)
    {
        List<Integer> children = new ArrayList<Integer>();
        joinTree.getTreeOrder(children);
        for (int child : children) {
            childFactors.set(child);
        }
    }
}

// End LoptMultiJoin.java