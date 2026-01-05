package com.netflix.conductor.tidb.mapper;

import com.baomidou.mybatisplus.mapper.BaseMapper;
import com.netflix.conductor.tidb.entity.TaskEntity;
import org.apache.ibatis.annotations.Mapper;


@Mapper
public interface TaskMapper extends BaseMapper<TaskEntity> {

}