package com.netflix.conductor.tidb.service;

import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.netflix.conductor.tidb.entity.TaskEntity;
import com.netflix.conductor.tidb.mapper.TaskMapper;
import org.springframework.stereotype.Service;

@Service
public class TaskService extends ServiceImpl<TaskMapper, TaskEntity> {


}
