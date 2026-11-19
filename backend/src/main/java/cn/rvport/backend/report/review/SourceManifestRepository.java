package cn.rvport.backend.report.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class SourceManifestRepository {
    private final DSLContext dsl;
    private final ObjectMapper mapper;
    public SourceManifestRepository(DSLContext dsl,ObjectMapper mapper) { this.dsl=dsl; this.mapper=mapper; }
    public Optional<ReportSourceSnapshotService.Manifest> find(UUID revision) {
        return dsl.fetchOptional("select manifest from source_revision_manifests where source_revision_id=?",revision).map(row->{
            try { return mapper.readValue(row.get("manifest",JSONB.class).data(),ReportSourceSnapshotService.Manifest.class); }
            catch(java.io.IOException ex) { throw new IllegalStateException("SOURCE_MANIFEST_INVALID",ex); }
        });
    }
    public ReportSourceSnapshotService.Manifest save(UUID project,ReportSourceSnapshotService.Manifest manifest) {
        try {
            dsl.execute("""
                    insert into source_revision_manifests(project_id,source_revision_id,manifest)
                    values(?,?,?::jsonb) on conflict(source_revision_id) do nothing
                    """,project,manifest.sourceRevisionId(),JSONB.valueOf(mapper.writeValueAsString(manifest)));
            return find(manifest.sourceRevisionId()).orElseThrow();
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("SOURCE_MANIFEST_INVALID",ex); }
    }
}
