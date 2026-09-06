package volkovandr.hauptbuch.importer;

import static org.mockito.Mockito.inOrder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import volkovandr.hauptbuch.importer.repository.ImportAccountRepository;
import volkovandr.hauptbuch.importer.repository.ImportCategoryRepository;
import volkovandr.hauptbuch.importer.repository.ImportFileRepository;

/**
 * Unit tier (CLAUDE.md §6): {@link ImportStagingPurge} clears a committed campaign's staging in the
 * right order — the duplicate-scan snapshot, then the category map (its tag junction cascades),
 * then the account map, then the files (transactions and postings cascade) (plan f2).
 */
@ExtendWith(MockitoExtension.class)
class ImportStagingPurgeTest {

  @Mock ImportFileRepository importFileRepository;
  @Mock ImportAccountRepository importAccountRepository;
  @Mock ImportCategoryRepository importCategoryRepository;
  @Mock ImportDuplicateScanService importDuplicateScanService;

  @Test
  void purgesEveryStagingTableForTheSession() {
    ImportStagingPurge purge =
        new ImportStagingPurge(
            importFileRepository,
            importAccountRepository,
            importCategoryRepository,
            importDuplicateScanService);

    purge.purge(9L);

    InOrder order =
        inOrder(
            importDuplicateScanService,
            importCategoryRepository,
            importAccountRepository,
            importFileRepository);
    order.verify(importDuplicateScanService).clearSnapshot(9L);
    order.verify(importCategoryRepository).deleteBySession(9L);
    order.verify(importAccountRepository).deleteBySession(9L);
    order.verify(importFileRepository).deleteBySession(9L);
  }
}
