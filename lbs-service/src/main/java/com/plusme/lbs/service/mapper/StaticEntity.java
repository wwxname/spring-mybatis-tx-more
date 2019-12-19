package com.plusme.lbs.service.mapper;

import lombok.Data;
import lombok.ToString;


import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.io.Serializable;

/**
 * @author plusme
 * @create 2019-12-13 10:22
 */

@Entity(name = "static")
public class StaticEntity implements Serializable {
    @Id
    private Integer id;
    private Integer num;
    private String name;
}
