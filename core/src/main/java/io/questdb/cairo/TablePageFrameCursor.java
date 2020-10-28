package io.questdb.cairo;

import org.jetbrains.annotations.Nullable;

import io.questdb.cairo.sql.PageFrame;
import io.questdb.cairo.sql.PageFrameCursor;
import io.questdb.cairo.sql.SymbolTable;
import io.questdb.std.IntList;
import io.questdb.std.LongList;
import io.questdb.std.Misc;
import io.questdb.std.Unsafe;

public class TablePageFrameCursor implements PageFrameCursor {
    private final LongList columnFrameAddresses = new LongList();
    private final LongList columnFrameLengths = new LongList();
    private final LongList columnTops = new LongList();
    private final ReplicationPageFrame frame = new ReplicationPageFrame();

    private TableReader reader;
    private long maxRowsPerFrame;
    private IntList columnIndexes;
    private IntList columnSizes;
    private int columnCount;

    private boolean moveToNextPartition;
    private int partitionIndex;
    private int partitionCount;
    private int timestampColumnIndex;
    private long frameFirstRow;
    private long nPartitionRows;
    private long firstTimestamp = Long.MIN_VALUE;
    private int columnBase;
    private boolean checkNFrameRowsForColumnTops;

    public TablePageFrameCursor() {
        super();
    }

    @Override
    public void close() {
        if (null != reader) {
            reader = Misc.free(reader);
            reader = null;
            columnIndexes = null;
            columnSizes = null;
        }
    }

    @Override
    public SymbolTable getSymbolTable(int i) {
        return reader.getSymbolMapReader(columnIndexes.getQuick(i));
    }

    TablePageFrameCursor of(
            TableReader reader, long maxRowsPerFrame, int timestampColumnIndex, IntList columnIndexes, IntList columnSizes, int partitionIndex, long partitionRowCount
    ) {
        of(reader, maxRowsPerFrame, timestampColumnIndex, columnIndexes, columnSizes);
        this.partitionIndex = partitionIndex - 1;
        frameFirstRow = partitionRowCount;

        return this;
    }

    public TablePageFrameCursor of(TableReader reader, long maxRowsPerFrame, int timestampColumnIndex, IntList columnIndexes, IntList columnSizes) {
        this.reader = reader;
        this.maxRowsPerFrame = maxRowsPerFrame;
        this.columnIndexes = columnIndexes;
        this.columnSizes = columnSizes;
        columnCount = columnIndexes.size();
        this.timestampColumnIndex = timestampColumnIndex;
        columnFrameAddresses.ensureCapacity(columnCount);
        columnFrameLengths.ensureCapacity(columnCount);
        columnTops.ensureCapacity(columnCount);
        toTop();
        return this;
    }

    @Override
    public @Nullable ReplicationPageFrame next() {
        while (!moveToNextPartition || ++partitionIndex < partitionCount) {
            if (moveToNextPartition) {
                nPartitionRows = reader.openPartition(partitionIndex);
                if (nPartitionRows <= frameFirstRow) {
                    frameFirstRow = 0;
                    continue;
                }
                columnBase = reader.getColumnBase(partitionIndex);
                checkNFrameRowsForColumnTops = false;
                for (int i = 0; i < columnCount; i++) {
                    int columnIndex = columnIndexes.get(i);
                    long columnTop = reader.getColumnTop(columnBase, columnIndex);
                    columnTops.setQuick(i, columnTop);
                    if (columnTop > 0) {
                        checkNFrameRowsForColumnTops = true;
                    }
                }
            }
            long nFrameRows = nPartitionRows - frameFirstRow;
            if (nFrameRows > maxRowsPerFrame) {
                nFrameRows = maxRowsPerFrame;
            }

            if (checkNFrameRowsForColumnTops) {
                checkNFrameRowsForColumnTops = false;
                long frameLastRow = frameFirstRow + nFrameRows - 1;
                for (int i = 0; i < columnCount; i++) {
                    long columnTop = columnTops.getQuick(i);
                    if (columnTop > frameFirstRow) {
                        checkNFrameRowsForColumnTops = true;
                        if (columnTop <= frameLastRow) {
                            nFrameRows = columnTop - frameFirstRow;
                            frameLastRow = frameFirstRow + nFrameRows - 1;
                        }
                    }
                }
            }

            for (int i = 0; i < columnCount; i++) {
                int columnIndex = columnIndexes.get(i);
                final long columnTop = columnTops.getQuick(i);
                final ReadOnlyColumn col = reader.getColumn(TableReader.getPrimaryColumnIndex(columnBase, columnIndex));

                if (columnTop <= frameFirstRow && col.getPageCount() > 0) {
                    assert col.getPageCount() == 1;
                    final long colFrameFirstRow = frameFirstRow - columnTop;
                    final long colFrameLastRow = colFrameFirstRow + nFrameRows;
                    final long colMaxRow = nPartitionRows - columnTop;

                    long columnPageAddress = col.getPageAddress(0);
                    long columnPageLength;

                    int columnType = reader.getMetadata().getColumnType(columnIndex);
                    switch (columnType) {
                        case ColumnType.STRING: {
                            final ReadOnlyColumn strLenCol = reader.getColumn(TableReader.getPrimaryColumnIndex(columnBase, columnIndex) + 1);
                            columnPageLength = calculateStringPagePosition(col, strLenCol, colFrameLastRow, colMaxRow);

                            if (colFrameFirstRow > 0) {
                                long columnPageBegin = calculateStringPagePosition(col, strLenCol, colFrameFirstRow, colMaxRow);
                                columnPageAddress += columnPageBegin;
                                columnPageLength -= columnPageBegin;
                            }

                            break;
                        }

                        case ColumnType.BINARY: {
                            final ReadOnlyColumn binLenCol = reader.getColumn(TableReader.getPrimaryColumnIndex(columnBase, columnIndex) + 1);
                            columnPageLength = calculateBinaryPagePosition(col, binLenCol, colFrameLastRow, colMaxRow);

                            if (colFrameFirstRow > 0) {
                                long columnPageBegin = calculateBinaryPagePosition(col, binLenCol, colFrameFirstRow, colMaxRow);
                                columnPageAddress += columnPageBegin;
                                columnPageLength -= columnPageBegin;
                            }

                            break;
                        }

                        default: {
                            int columnSizeBinaryPower = columnSizes.getQuick(i);
                            columnPageLength = colFrameLastRow << columnSizeBinaryPower;
                            if (colFrameFirstRow > 0) {
                                long columnPageBegin = colFrameFirstRow << columnSizeBinaryPower;
                                columnPageAddress += columnPageBegin;
                                columnPageLength -= columnPageBegin;
                            }
                        }
                    }

                    columnFrameAddresses.setQuick(i, columnPageAddress);
                    columnFrameLengths.setQuick(i, columnPageLength);

                    if (timestampColumnIndex == columnIndex) {
                        firstTimestamp = Unsafe.getUnsafe().getLong(columnPageAddress);
                    }
                } else {
                    columnFrameAddresses.setQuick(i, 0);
                    // Frame length is the number of rows missing from the top of the partition (i.e. the columnTop)
                    columnFrameLengths.setQuick(i, nFrameRows);
                }
            }

            frameFirstRow += nFrameRows;
            assert frameFirstRow <= nPartitionRows;
            if (frameFirstRow == nPartitionRows) {
                moveToNextPartition = true;
                frameFirstRow = 0;
            } else {
                moveToNextPartition = false;
            }
            return frame;
        }
        return null;
    }

    private long calculateBinaryPagePosition(final ReadOnlyColumn col, final ReadOnlyColumn binLenCol, long row, long maxRows) {
    	assert row > 0 && row <= maxRows;

        if (row < maxRows) {
            long binLenOffset = row << 3;
            return binLenCol.getLong(binLenOffset);
        }

        long binLenOffset = (row -1) << 3;
        long prevBinOffset = binLenCol.getLong(binLenOffset);
        long binLen =  col.getInt(prevBinOffset);
        long sz;
        if (binLen == TableUtils.NULL_LEN) {
            sz = Long.BYTES;
        } else {
            sz = Long.BYTES + binLen;
        }
        
        return prevBinOffset + sz;
    }

    private long calculateStringPagePosition(final ReadOnlyColumn col, final ReadOnlyColumn strLenCol, long row, long maxRows) {
        assert row > 0 && row <= maxRows;

        if (row < maxRows) {
            long strLenOffset = row << 3;
            return strLenCol.getLong(strLenOffset);
        }

        long strLenOffset = (row -1) << 3;
        long prevStrOffset = strLenCol.getLong(strLenOffset);
        long strLen = col.getInt(prevStrOffset);
        long sz;
        if (strLen == TableUtils.NULL_LEN) {
            sz = VirtualMemory.STRING_LENGTH_BYTES;
        } else {
            sz = VirtualMemory.STRING_LENGTH_BYTES + 2 * strLen;
        }

        return prevStrOffset + sz;
    }

    @Override
    public void toTop() {
        partitionIndex = -1;
        moveToNextPartition = true;
        partitionCount = reader.getPartitionCount();
        firstTimestamp = Long.MIN_VALUE;
    }

    @Override
    public long size() {
        return reader.size();
    }

    public class ReplicationPageFrame implements PageFrame {

        @Override
        public long getPageAddress(int i) {
            return columnFrameAddresses.getQuick(i);
        }

        @Override
        public long getFirstTimestamp() {
            return firstTimestamp;
        }

        public int getPartitionIndex() {
            return partitionIndex;
        }

        @Override
        public long getPageSize(int i) {
            return columnFrameLengths.getQuick(i);
        }
    }
}