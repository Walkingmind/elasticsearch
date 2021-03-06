package org.elasticsearch.index.search.nested;

import org.apache.lucene.index.AtomicReaderContext;
import org.apache.lucene.search.DocIdSet;
import org.apache.lucene.search.FieldComparator;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.SortField;
import org.apache.lucene.util.FixedBitSet;
import org.elasticsearch.ElasticSearchIllegalArgumentException;
import org.elasticsearch.common.lucene.docset.DocIdSets;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.fieldcomparator.SortMode;

import java.io.IOException;

/**
 */
public class NestedFieldComparatorSource extends IndexFieldData.XFieldComparatorSource {

    private final SortMode sortMode;
    private final IndexFieldData.XFieldComparatorSource wrappedSource;
    private final Filter rootDocumentsFilter;
    private final Filter innerDocumentsFilter;

    public NestedFieldComparatorSource(SortMode sortMode, IndexFieldData.XFieldComparatorSource wrappedSource, Filter rootDocumentsFilter, Filter innerDocumentsFilter) {
        this.sortMode = sortMode;
        this.wrappedSource = wrappedSource;
        this.rootDocumentsFilter = rootDocumentsFilter;
        this.innerDocumentsFilter = innerDocumentsFilter;
    }

    @Override
    public FieldComparator<?> newComparator(String fieldname, int numHits, int sortPos, boolean reversed) throws IOException {
        // +1: have one spare slot for value comparison between inner documents.
        FieldComparator wrappedComparator = wrappedSource.newComparator(fieldname, numHits + 1, sortPos, reversed);
        switch (sortMode) {
            case MAX:
                return new NestedFieldComparator.Highest(wrappedComparator, rootDocumentsFilter, innerDocumentsFilter, numHits);
            case MIN:
                return new NestedFieldComparator.Lowest(wrappedComparator, rootDocumentsFilter, innerDocumentsFilter, numHits);
            default:
                throw new ElasticSearchIllegalArgumentException(
                        String.format("Unsupported sort_mode[%s] for nested type", sortMode)
                );
        }
    }

    @Override
    public SortField.Type reducedType() {
        return wrappedSource.reducedType();
    }
}

abstract class NestedFieldComparator extends FieldComparator {

    final Filter rootDocumentsFilter;
    final Filter innerDocumentsFilter;
    final int spareSlot;

    FieldComparator wrappedComparator;
    FixedBitSet rootDocuments;
    FixedBitSet innerDocuments;

    NestedFieldComparator(FieldComparator wrappedComparator, Filter rootDocumentsFilter, Filter innerDocumentsFilter, int spareSlot) {
        this.wrappedComparator = wrappedComparator;
        this.rootDocumentsFilter = rootDocumentsFilter;
        this.innerDocumentsFilter = innerDocumentsFilter;
        this.spareSlot = spareSlot;
    }

    @Override
    public int compare(int slot1, int slot2) {
        return wrappedComparator.compare(slot1, slot2);
    }

    @Override
    public void setBottom(int slot) {
        wrappedComparator.setBottom(slot);
    }

    @Override
    public FieldComparator setNextReader(AtomicReaderContext context) throws IOException {
        DocIdSet innerDocuments = innerDocumentsFilter.getDocIdSet(context, null);
        if (DocIdSets.isEmpty(innerDocuments)) {
            this.innerDocuments = null;
        } else if (innerDocuments instanceof FixedBitSet) {
            this.innerDocuments = (FixedBitSet) innerDocuments;
        } else {
            this.innerDocuments = DocIdSets.toFixedBitSet(innerDocuments.iterator(), context.reader().maxDoc());
        }
        DocIdSet rootDocuments = rootDocumentsFilter.getDocIdSet(context, null);
        if (DocIdSets.isEmpty(rootDocuments)) {
            this.rootDocuments = null;
        } else if (rootDocuments instanceof FixedBitSet) {
            this.rootDocuments = (FixedBitSet) rootDocuments;
        } else {
            this.rootDocuments = DocIdSets.toFixedBitSet(rootDocuments.iterator(), context.reader().maxDoc());
        }

        wrappedComparator = wrappedComparator.setNextReader(context);
        return this;
    }

    @Override
    public Object value(int slot) {
        return wrappedComparator.value(slot);
    }

    final static class Lowest extends NestedFieldComparator {

        Lowest(FieldComparator wrappedComparator, Filter parentFilter, Filter childFilter, int spareSlot) {
            super(wrappedComparator, parentFilter, childFilter, spareSlot);
        }

        @Override
        public int compareBottom(int rootDoc) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return 0;
            }

            // We need to copy the lowest value from all nested docs into slot.
            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return 0;
            }

            // We only need to emit a single cmp value for any matching nested doc
            int cmp = wrappedComparator.compareBottom(nestedDoc);
            if (cmp > 0) {
                return cmp;
            }

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return cmp;
                }
                int cmp1 = wrappedComparator.compareBottom(nestedDoc);
                if (cmp1 > 0) {
                    return cmp1;
                } else {
                    if (cmp1 == 0) {
                        cmp = 0;
                    }
                }
            }
        }

        @Override
        public void copy(int slot, int rootDoc) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return;
            }

            // We need to copy the lowest value from all nested docs into slot.
            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return;
            }
            wrappedComparator.copy(spareSlot, nestedDoc);
            wrappedComparator.copy(slot, nestedDoc);

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return;
                }
                wrappedComparator.copy(spareSlot, nestedDoc);
                if (wrappedComparator.compare(spareSlot, slot) < 0) {
                    wrappedComparator.copy(slot, nestedDoc);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compareDocToValue(int rootDoc, Object value) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return 0;
            }

            // We need to copy the lowest value from all nested docs into slot.
            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return 0;
            }

            // We only need to emit a single cmp value for any matching nested doc
            int cmp = wrappedComparator.compareBottom(nestedDoc);
            if (cmp > 0) {
                return cmp;
            }

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return cmp;
                }
                int cmp1 = wrappedComparator.compareDocToValue(nestedDoc, value);
                if (cmp1 > 0) {
                    return cmp1;
                } else {
                    if (cmp1 == 0) {
                        cmp = 0;
                    }
                }
            }
        }

    }

    final static class Highest extends NestedFieldComparator {

        Highest(FieldComparator wrappedComparator, Filter parentFilter, Filter childFilter, int spareSlot) {
            super(wrappedComparator, parentFilter, childFilter, spareSlot);
        }

        @Override
        public int compareBottom(int rootDoc) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return 0;
            }

            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return 0;
            }

            int cmp = wrappedComparator.compareBottom(nestedDoc);
            if (cmp < 0) {
                return cmp;
            }

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return cmp;
                }
                int cmp1 = wrappedComparator.compareBottom(nestedDoc);
                if (cmp1 < 0) {
                    return cmp1;
                } else {
                    if (cmp1 == 0) {
                        cmp = 0;
                    }
                }
            }
        }

        @Override
        public void copy(int slot, int rootDoc) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return;
            }

            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return;
            }
            wrappedComparator.copy(spareSlot, nestedDoc);
            wrappedComparator.copy(slot, nestedDoc);

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return;
                }
                wrappedComparator.copy(spareSlot, nestedDoc);
                if (wrappedComparator.compare(spareSlot, slot) > 0) {
                    wrappedComparator.copy(slot, nestedDoc);
                }
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compareDocToValue(int rootDoc, Object value) throws IOException {
            if (rootDoc == 0 || rootDocuments == null || innerDocuments == null) {
                return 0;
            }

            int prevRootDoc = rootDocuments.prevSetBit(rootDoc - 1);
            int nestedDoc = innerDocuments.nextSetBit(prevRootDoc + 1);
            if (nestedDoc >= rootDoc || nestedDoc == -1) {
                return 0;
            }

            int cmp = wrappedComparator.compareBottom(nestedDoc);
            if (cmp < 0) {
                return cmp;
            }

            while (true) {
                nestedDoc = innerDocuments.nextSetBit(nestedDoc + 1);
                if (nestedDoc >= rootDoc || nestedDoc == -1) {
                    return cmp;
                }
                int cmp1 = wrappedComparator.compareDocToValue(nestedDoc, value);
                if (cmp1 < 0) {
                    return cmp1;
                } else {
                    if (cmp1 == 0) {
                        cmp = 0;
                    }
                }
            }
        }

    }

}