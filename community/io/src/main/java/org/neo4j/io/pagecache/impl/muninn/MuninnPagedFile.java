/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.io.pagecache.impl.muninn;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveLongIntMap;
import org.neo4j.io.pagecache.PageCacheMonitor;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.io.pagecache.PageEvictionCallback;
import org.neo4j.io.pagecache.PageSwapper;
import org.neo4j.io.pagecache.PageSwapperFactory;
import org.neo4j.io.pagecache.PagedFile;
import org.neo4j.io.pagecache.impl.muninn.jsr166e.StampedLock;

class MuninnPagedFile implements PagedFile
{
    private static int stripeFactor = Integer.getInteger(
            "org.neo4j.io.pagecache.impl.muninn.MuninnPagedFile.stripeFactor", 8 );
    static final int translationTableStripeLevel = 1 << stripeFactor;
    static final int translationTableStripeMask = translationTableStripeLevel - 1;

    private static final long referenceCounterOffset =
            UnsafeUtil.getFieldOffset( MuninnPagedFile.class, "referenceCounter" );
    private static final long lastPageIdOffset =
            UnsafeUtil.getFieldOffset( MuninnPagedFile.class, "lastPageId" );

    final MuninnPageCache pageCache;
    // These are the globally shared pages of the cache:
    final MuninnPage[] cachePages;
    // This is the table where we translate file-page-ids to cache-page-ids:
    final int pageSize;
    // Global linked list of free pages
    final AtomicReference<MuninnPage> freelist;
    final PageCacheMonitor monitor;

    final PrimitiveLongIntMap[] translationTables;
    final StampedLock[] translationTableLocks;

    final PageSwapper swapper;
    final PageFlusher flusher;
    private final MuninnCursorFreelist readCursors;
    private final MuninnCursorFreelist writeCursors;

    // Accessed via Unsafe
    private volatile int referenceCounter;
    private volatile long lastPageId;

    MuninnPagedFile(
            File file,
            MuninnPageCache pageCache,
            int pageSize,
            PageSwapperFactory swapperFactory,
            AtomicReference<MuninnPage> freelist,
            PageCacheMonitor monitor ) throws IOException
    {
        this.pageCache = pageCache;
        this.cachePages = pageCache.pages;
        this.pageSize = pageSize;
        this.freelist = freelist;
        this.monitor = monitor;

        // The translation table and its locks are striped to reduce lock
        // contention.
        // This is important as both eviction and page faulting will grab
        // these locks, and will hold them for the duration of their respective
        // operation.
        translationTables = new PrimitiveLongIntMap[translationTableStripeLevel];
        translationTableLocks = new StampedLock[translationTableStripeLevel];
        for ( int i = 0; i < translationTableStripeLevel; i++ )
        {
            translationTables[i] = Primitive.longIntMap( 32 );
            translationTableLocks[i] = new StampedLock();
        }
        PageEvictionCallback onEviction = new MuninnPageEvictionCallback(
                translationTables, translationTableLocks );
        swapper = swapperFactory.createPageSwapper( file, pageSize, onEviction );
        flusher = new PageFlusher( cachePages, swapper );
        lastPageId = swapper.getLastPageId();

        readCursors = new MuninnCursorFreelist()
        {
            @Override
            protected MuninnPageCursor createNewCursor()
            {
                return new MuninnReadPageCursor( this );
            }
        };
        writeCursors = new MuninnCursorFreelist()
        {
            @Override
            protected MuninnPageCursor createNewCursor()
            {
                return new MuninnWritePageCursor( this );
            }
        };
    }

    @Override
    public PageCursor io( long pageId, int pf_flags ) throws IOException
    {
        int lockMask = PF_EXCLUSIVE_LOCK | PF_SHARED_LOCK;
        if ( (pf_flags & lockMask) == 0 )
        {
            throw new IllegalArgumentException(
                    "Must specify either PF_EXCLUSIVE_LOCK or PF_SHARED_LOCK" );
        }
        if ( (pf_flags & lockMask) == lockMask )
        {
            throw new IllegalArgumentException(
                    "Cannot specify both PF_EXCLUSIVE_LOCK and PF_SHARED_LOCK" );
        }
        MuninnPageCursor cursor;
        if ( (pf_flags & PF_SHARED_LOCK) == 0 )
        {
            cursor = writeCursors.takeCursor();
        }
        else
        {
            cursor = readCursors.takeCursor();
        }

        cursor.initialise( this, pageId, pf_flags );
        cursor.rewind();
        return cursor;
    }

    @Override
    public int pageSize()
    {
        return pageSize;
    }

    public void close() throws IOException
    {
        flush();
        swapper.close();
    }

    @Override
    public void flush() throws IOException
    {
        for ( int i = 0; i < translationTableStripeLevel; i++ )
        {
            PrimitiveLongIntMap translationTable = translationTables[i];
            StampedLock translationTableLock = translationTableLocks[i];

            long stamp = translationTableLock.readLock();
            try
            {
                translationTable.visitEntries( flusher );
            }
            finally
            {
                translationTableLock.unlockRead( stamp );
            }
        }
        force();
    }

    @Override
    public void force() throws IOException
    {
        swapper.force();
    }

    @Override
    public long getLastPageId() throws IOException
    {
        return lastPageId;
    }

    /**
     * Make sure that the lastPageId is at least the given pageId
     */
    long increaseLastPageIdTo( long newLastPageId )
    {
        long current;
        do
        {
            current = lastPageId;
        }
        while ( current < newLastPageId
                && !UnsafeUtil.compareAndSwapLong( this, lastPageIdOffset, current, newLastPageId ) );
        return lastPageId;
    }

    /**
     * Atomically increment the reference count for this mapped file.
     */
    void incrementReferences()
    {
        UnsafeUtil.getAndAddInt( this, referenceCounterOffset, 1 );
    }

    /**
     * Atomically decrement the reference count. Returns true if this was the
     * last reference.
     */
    boolean decrementReferences()
    {
        // compares with 1 because getAndAdd returns the old value, and a 1
        // means the value is now 0.
        return UnsafeUtil.getAndAddInt( this, referenceCounterOffset, -1 ) <= 1;
    }
}
