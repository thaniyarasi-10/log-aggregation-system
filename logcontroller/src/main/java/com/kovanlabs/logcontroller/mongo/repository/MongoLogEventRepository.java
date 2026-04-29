package com.kovanlabs.logcontroller.mongo.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.kovanlabs.logcontroller.model.MongoLogEvent;

public interface MongoLogEventRepository extends MongoRepository<MongoLogEvent, String> {
}
