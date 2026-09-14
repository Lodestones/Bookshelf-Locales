package gg.lode.bookshelflocales.migration;

/**
 * One step between two locale versions, run the same way a config migration is:
 * a file at 1 upgrading to 3 runs the step for 2 and then the step for 3, so a
 * server that skipped a release still arrives by the same route as one that did
 * not.
 *
 * <p>A step runs against the owner's file and against the recorded defaults
 * alike. Migrating both is what keeps the merge honest afterwards: a value the
 * owner never touched has to still match the snapshot once both have been
 * rewritten, or it would look edited and never be corrected again.
 */
@FunctionalInterface
public interface LocaleMigration {

    void apply(LocaleEdit locale);
}
