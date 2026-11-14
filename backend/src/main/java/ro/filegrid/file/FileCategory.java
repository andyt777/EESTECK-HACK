package ro.filegrid.file;

/** The four data categories handled by FileGrid (the four "houses" in the UI). */
public enum FileCategory {
    METER_READING("meters"),
    FAULT_RECORD("faults"),
    CONNECTION_DOCUMENT("documents"),
    DRONE_VIDEO("videos");

    private final String storageSegment;

    FileCategory(String storageSegment) {
        this.storageSegment = storageSegment;
    }

    /**
     * First segment of every storage key of this category. It maps to an S3
     * bucket ({@code filegrid-<segment>}) and to a top-level FTP directory.
     */
    public String storageSegment() {
        return storageSegment;
    }
}
