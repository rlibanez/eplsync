package com.rlibanez.eplsync.jobs;

import jakarta.persistence.*;

/** Read-only projection used by retention to keep a source while its retry still exists. */
@Entity(name="JobRetryLink")
@Table(name="job_retry_jobs")
@IdClass(JobRetryLink.Key.class)
@org.hibernate.annotations.Immutable
public class JobRetryLink {
    @Id private String kind;
    @Id private String childId;
    // Read by Hibernate through the retention queries, rather than Java accessors.
    @SuppressWarnings("unused") private String parentId;
    @SuppressWarnings("unused") private String rootId;
    public static class Key implements java.io.Serializable {
        private static final long serialVersionUID=1L;
        public String kind;
        public String childId;
        public Key() {}
        @Override public boolean equals(Object other) {return other instanceof Key key && java.util.Objects.equals(kind,key.kind) && java.util.Objects.equals(childId,key.childId);}
        @Override public int hashCode() {return java.util.Objects.hash(kind,childId);}
    }
}
