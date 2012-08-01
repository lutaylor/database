/**

Copyright (C) SYSTAP, LLC 2006-2011.  All rights reserved.

Contact:
     SYSTAP, LLC
     4501 Tower Road
     Greensboro, NC 27410
     licenses@bigdata.com

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; version 2 of the License.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
/*
 * Created on Aug 1, 2012
 */
package com.bigdata.btree;

import com.bigdata.striterator.ICloseableIterator;

/**
 * Generic data access methods defined for all persistence capable data
 * structures.
 * 
 * TODO There should be a high level method to insert objects into the index
 * (index "entries" not tuples - the index will need to compute the appropriate
 * key, etc. in an implementation dependent manner).
 */
public interface ISimpleIndexAccess {

    /**
     * Return the #of entries in the index.
     * <p>
     * Note: If the index supports deletion markers then the range count will be
     * an upper bound and may double count tuples which have been overwritten,
     * including the special case where the overwrite is a delete.
     * 
     * @return The #of tuples in the index.
     * 
     * @see IRangeQuery#rangeCount()
     */
    public long rangeCount();

    /**
     * Visit all entries in the index in the natural order of the index
     * (dereferencing visited tuples to the application objects stored within
     * those tuples).
     */
    public ICloseableIterator<?> scan();

    /**
     * Remove all entries in the index.
     */
    public void removeAll();

}
