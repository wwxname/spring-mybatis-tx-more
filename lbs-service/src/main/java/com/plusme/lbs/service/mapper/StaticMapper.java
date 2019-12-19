package com.plusme.lbs.service.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * @author plusme
 * @create 2019-12-13 10:21
 */
@Mapper
@Repository
public interface StaticMapper {
    default List<StaticEntity> getDefault() {
        return selectAll(1);
    }

    @Select(value = "select * from static where id >#{id}")
    List<StaticEntity> selectAll(@Param(value = "id") Integer id);

    String selectNameByIdForMap(Map<String, Integer> param);

    @Select(value = "select * from static where id = #{id}")
    StaticEntity selectOne(@Param(value = "id") Integer id);
}
