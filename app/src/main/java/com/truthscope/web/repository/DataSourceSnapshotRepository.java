package com.truthscope.web.repository;

import com.truthscope.web.entity.DataSourceSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataSourceSnapshotRepository extends JpaRepository<DataSourceSnapshot, UUID> {

  List<DataSourceSnapshot> findByAdapterNameAndQueryHash(String adapterName, String queryHash);
}
