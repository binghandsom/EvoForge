package com.evoforge.task

interface EvoTaskStore {
    EvoTask find(String taskId)
    List<EvoTask> listRecent(int limit)
}
