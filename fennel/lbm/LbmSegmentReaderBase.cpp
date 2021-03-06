/*
// Licensed to DynamoBI Corporation (DynamoBI) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  DynamoBI licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at

//   http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
*/

#include "fennel/common/CommonPreamble.h"
#include "fennel/exec/ExecStreamBufAccessor.h"
#include "fennel/lbm/LbmSegmentReader.h"
#include "fennel/lbm/LbmTupleReader.h"

FENNEL_BEGIN_CPPFILE("$Id$");

void LbmSegmentReaderBase::init(
    SharedExecStreamBufAccessor &pInAccessorInit,
    TupleData &bitmapSegTuple)
{
    LbmStreamTupleReader *pNewReader = new LbmStreamTupleReader();
    pNewReader->init(pInAccessorInit, bitmapSegTuple);
    SharedLbmTupleReader pTupleReader(pNewReader);
    init(pTupleReader, bitmapSegTuple, false, NULL);
}

void LbmSegmentReaderBase::init(
    SharedExecStreamBufAccessor &pInAccessorInit,
    TupleData &bitmapSegTuple,
    bool setBitmapInit,
    boost::dynamic_bitset<> *pBitmapInit)
{
    LbmStreamTupleReader *pNewReader = new LbmStreamTupleReader();
    pNewReader->init(pInAccessorInit, bitmapSegTuple);
    SharedLbmTupleReader pTupleReader(pNewReader);
    init(pTupleReader, bitmapSegTuple, setBitmapInit, pBitmapInit);
}

void LbmSegmentReaderBase::init(
    SharedLbmTupleReader &pTupleReaderInit,
    TupleData &bitmapSegTuple)
{
    init(pTupleReaderInit, bitmapSegTuple, false, NULL);
}

void LbmSegmentReaderBase::init(
    SharedLbmTupleReader &pTupleReaderInit,
    TupleData &bitmapSegTuple,
    bool setBitmapInit,
    boost::dynamic_bitset<> *pBitmapInit)
{
    pTupleReader = pTupleReaderInit;
    pBitmapSegTuple = &bitmapSegTuple;
    iSrid = bitmapSegTuple.size() - 3;
    iSegmentDesc = iSrid + 1;
    iSegments = iSrid + 2;
    byteSegLen = 0;
    byteSegOffset = LbmByteNumber(0);
    pSegStart = NULL;
    pSegDescStart = NULL;
    pSegDescEnd = NULL;
    zeroBytes = 0;
    tupleChange = false;
    setBitmap = setBitmapInit;
    pBitmap = pBitmapInit;
    maxRidSet = LcsRid(0);
}

ExecStreamResult LbmSegmentReaderBase::readBitmapSegTuple()
{
    ExecStreamResult rc = pTupleReader->read(pBitmapSegTuple);
    if (rc != EXECRC_YIELD) {
        return rc;
    }

    // extract starting rid and compute its equivalent byte segment number
    startRID = *reinterpret_cast<LcsRid const *>
        ((*pBitmapSegTuple)[iSrid].pData);
    byteSegOffset = ridToByteNumber(startRID);
    zeroBytes = 0;

    // determine where the segment descriptor starts and ends, if there is
    // one
    pSegDescStart = (PBuffer) (*pBitmapSegTuple)[iSegmentDesc].pData;
    // descriptor can be NULL
    if (pSegDescStart != NULL) {
        pSegDescEnd = pSegDescStart + (*pBitmapSegTuple)[iSegmentDesc].cbData;
    } else {
        pSegDescEnd = NULL;
    }

    // determine where the bitmap segment starts and its length
    if ((*pBitmapSegTuple)[iSegments].pData) {
        // note that bit segment is stored backwards
        byteSegLen = (*pBitmapSegTuple)[iSegments].cbData;
        pSegStart = (PBuffer)
            ((*pBitmapSegTuple)[iSegments].pData + byteSegLen - 1);
    } else {
        // singletons do not have a corresponding bitmap, so create one
        byteSegLen = 1;
        pSegStart = &singleton;
        singleton = (uint8_t)(1 << (opaqueToInt(startRID) % LbmOneByteSize));
        if (setBitmap) {
            pBitmap->set(opaqueToInt(startRID % pBitmap->size()));
            if (startRID > maxRidSet) {
                maxRidSet = startRID;
            }
        }
    }

    if (!pSegDescStart) {
        // For bitmaps without a descriptor, set the bits in the segment.
        // Bitmaps with descriptors will be handled as we advance through
        // each segment within the bitmap.
        setBitsRead(startRID, pSegStart, byteSegLen);
    }

    tupleChange = true;
    return EXECRC_YIELD;
}

void LbmSegmentReaderBase::setBitsRead(
    LcsRid startRid,
    PBuffer segStart,
    uint segLen)
{
    if (setBitmap) {
        uint bitmapSize = pBitmap->size();
        PBuffer seg = segStart;
        LcsRid rid = startRid;
        for (uint i = 0; i < segLen; i++) {
            uint8_t byte = *(uint8_t *) seg;
            for (uint j = 0; j < LbmOneByteSize; j++) {
                if (byte & 1) {
                    pBitmap->set(opaqueToInt(rid % bitmapSize));
                    if (rid > maxRidSet) {
                        maxRidSet = rid;
                    }
                }
                byte = byte >> 1;
                rid++;
            }
            seg--;
        }
    }
}

void LbmSegmentReaderBase::advanceSegment()
{
    // first, advance byte segment offset and segment pointer by the
    // length of the remaining part of the previous segment and the
    // trailing zero bytes
    byteSegOffset += byteSegLen + zeroBytes;
    pSegStart -= byteSegLen;

    // then, read the segment descriptor to determine where the segment
    // starts and its length; also advance the segment descriptor to the
    // next descriptor
    readSegDescAndAdvance(pSegDescStart, byteSegLen, zeroBytes);

    // keep track of the bits in the segment that we just advanced to
    setBitsRead(byteNumberToRid(byteSegOffset), pSegStart, byteSegLen);
}

bool LbmSegmentReaderBase::getTupleChange()
{
    return tupleChange;
}

void LbmSegmentReaderBase::resetChangeListener()
{
    tupleChange = false;
}

LcsRid LbmSegmentReaderBase::getMaxRidSet()
{
    return maxRidSet;
}

FENNEL_END_CPPFILE("$Id$");

// End LbmSegmentReaderBase.cpp
