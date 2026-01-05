package com.netflix.conductor.tidb.service;

import com.baomidou.mybatisplus.service.impl.ServiceImpl;
import com.netflix.conductor.tidb.entity.WorkflowEntity;
import com.netflix.conductor.tidb.mapper.WorkflowMapper;
import org.springframework.stereotype.Service;

@Service
public class WorkflowService extends ServiceImpl<WorkflowMapper, WorkflowEntity> {


}
