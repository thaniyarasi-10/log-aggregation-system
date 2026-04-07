# Log aggregation system

A centralized log aggregation system that collects logs from multiple services, streams them through Kafka, and stores them in Elasticsearch for real-time search and monitoring.

---

##  Tech Stack

- Backend: Spring Boot (Java)
- Messaging: Apache Kafka (KRaft mode)
- Log Shipper: Filebeat
- Storage & Search: Elasticsearch
- Dashboad

---

## 📂 Project Structure
    
    log-aggregation-system/
    ├── log-dashboard-frontend/        # Frontend dashboard (HTML + TS + CSS)
    │   ├── components/                # UI components (TS)
    │   │   ├── alertsPanel.ts
    │   │   ├── filters.ts
    │   │   ├── logTable.ts
    │   │   └── metricsCards.ts
    │   │
    │   ├── css/                       # Styles and animations
    │   │   ├── animations.css
    │   │   └── styles.css
    │   │
    │   ├── ts/                        # Core frontend logic
    │   │   ├── api.ts
    │   │   ├── charts.ts
    │   │   ├── dashboard.ts
    │   │   ├── realtime.ts
    │   │   ├── types.ts
    │   │   └── utils.ts
    │   │
    │   ├── dist/                      # Compiled JS output
    │   │   ├── components/
    │   │   └── ts/
    │   │
    │   ├── index.html
    │   ├── package.json
    │   └── tsconfig.json
    │
    ├── logcontroller/                 # Backend (Spring MVC)
    │   ├── src/main/java/com/kovanlabs/logcontroller/
    │   │   ├── config/                # Security & config classes
    │   │   ├── consumer/              # Kafka consumers
    │   │   ├── controller/            # REST APIs
    │   │   ├── model/                 # Data models
    │   │   ├── parser/                # Log parsing logic
    │   │   ├── repository/            # Elasticsearch integration
    │   │   └── service/               # Business logic
    │   │
    │   ├── pom.xml
    │   └── target/
    │
    ├── kafka/                         # Kafka setup (local)
    ├── filebeat/                      # Filebeat configuration
    ├── logs/                          # Log files from services
    │
    ├── .gitignore
    └── README.md
---

##  Prerequisites

Make sure you have:

- Java 17+
- Maven
- Apache Kafka
- Elasticsearch
- Filebeat

---

## 🔧 Step 1: Setup Kafka (KRaft Mode)

cd kafka

Generate cluster ID:
bin/kafka-storage.sh random-uuid

Format storage:
bin/kafka-storage.sh format -t <CLUSTER_ID> -c config/kraft/server.properties

Start Kafka:
bin/kafka-server-start.sh config/kraft/server.properties

---

## 🔧 Step 2: Create Kafka Topic

bin/kafka-topics.sh --create \
--topic logs-topic \
--bootstrap-server localhost:9092 \
--partitions 1 \
--replication-factor 1

---

## 🔧 Step 3: Setup Elasticsearch

cd elasticsearch

Start Elasticsearch:
bin/elasticsearch

Verify:
http://localhost:9200

---

## 🔧 Step 4: Setup Filebeat

Download Filebeat:
https://www.elastic.co/downloads/beats/filebeat

Configure filebeat.yml:

filebeat.inputs:
  - type: filestream
    paths:
      - D:/logs/**/*.log

output.kafka:
  hosts: ["localhost:9092"]
  topic: "logs-topic"

Start Filebeat:
filebeat.exe -e 

---

## 🔧 Step 5: Spring Boot Setup

Add dependencies in pom.xml:

<dependencies>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.kafka</groupId>
        <artifactId>spring-kafka</artifactId>
    </dependency>

    <dependency>
        <groupId>org.elasticsearch.client</groupId>
        <artifactId>elasticsearch-rest-high-level-client</artifactId>
        <version>7.17.0</version>
    </dependency>

    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
    </dependency>

</dependencies>

---

## 🔧 Step 6: Configure application.yml

spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: log-group
      auto-offset-reset: earliest

elasticsearch:
  host: localhost
  port: 9200

---

##  Application Flow

1. Logs are generated from multiple services  
2. Filebeat reads log files continuously  
3. Filebeat sends logs to Kafka topic (logs-topic)  
4. Spring Boot consumes logs from Kafka  
5. Logs are processed into structured format  
6. Logs are stored in Elasticsearch  
7. Logs can be searched via APIs or Kibana  

---

##  Sample Log Format

{
  "timestamp": "2026-03-28T10:00:00.000Z",
  "level": "ERROR",
  "service": "payment-service",
  "instance": "pod-xyz",
  "message": "Payment failed"
}

---

##  Run the Project

Start in this order:

1. Kafka  
2. Elasticsearch  
3. Filebeat  
4. Spring Boot  

---

##  Verify Logs

http://localhost:9200/logs/_search

---

## 📊 Optional: Kibana (Instead of frontend)

cd kibana  
bin/kibana  

Open:
http://localhost:5601

---

##  Features

- Real-time log ingestion  
- Centralized logging  
- Kafka-based streaming  
- Fast search with Elasticsearch  
- Supports multiple services  
