package com.plusme.lbs.service.res;

import com.plusme.lbs.service.mapper.StaticEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * @author plusme
 * @create 2019-12-14 9:03
 */
@Repository
public interface StaticRepository extends JpaRepository<StaticEntity, Integer> {

    default StaticEntity selectOneById(Integer id) {
        int i =0;
        //int j = 1/i;
        return findById(id).get();
    }
//    @Query("select id,num,name from sta")
//    public StaticEntity selectTest();
}
