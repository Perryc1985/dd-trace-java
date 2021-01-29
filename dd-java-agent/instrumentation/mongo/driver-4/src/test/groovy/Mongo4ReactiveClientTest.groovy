import com.mongodb.client.result.DeleteResult
import com.mongodb.client.result.UpdateResult
import com.mongodb.reactivestreams.client.MongoClient
import com.mongodb.reactivestreams.client.MongoClients
import com.mongodb.reactivestreams.client.MongoCollection
import com.mongodb.reactivestreams.client.MongoDatabase
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDSpanTypes
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.core.DDSpan
import org.bson.BsonDocument
import org.bson.BsonString
import org.bson.Document
import org.reactivestreams.Subscriber
import org.reactivestreams.Subscription
import spock.lang.Shared
import spock.lang.Timeout

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

@Timeout(10)
class Mongo4ReactiveClientTest extends MongoBaseTest {

  @Shared
  MongoClient client

  def setup() throws Exception {
    client = MongoClients.create("mongodb://localhost:$port/?appname=some-instance")
  }

  def cleanup() throws Exception {
    client?.close()
    client = null
  }

  MongoCollection<Document> setupCollection(String dbName, String collectionName) {
    MongoCollection<Document> collection = runUnderTrace("setup") {
      MongoDatabase db = client.getDatabase(dbName)
      def latch = new CountDownLatch(1)
      // This creates a trace that isn't linked to the parent... using NIO internally that we don't handle.
      db.createCollection(collectionName).subscribe(toSubscriber { latch.countDown() })
      latch.await()
      return db.getCollection(collectionName)
    }
    TEST_WRITER.waitForTraces(2)
    TEST_WRITER.clear()
    return collection
  }

  void insertDocument(MongoCollection<Document> collection, Document document, Subscriber<?> subscriber) {
    def publisher = collection.insertOne(document)
    if (null != subscriber) {
      publisher.subscribe(subscriber)
    } else {
      def latch = new CountDownLatch(1)
      publisher.subscribe(toSubscriber {
        latch.countDown()
      })
      latch.await()
      TEST_WRITER.waitForTraces(1)
      TEST_WRITER.clear()
    }
  }

  def "test create collection"() {
    setup:
    MongoDatabase db = client.getDatabase(dbName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create") {
          assert it.replaceAll(" ", "") == "{\"create\":\"$collectionName\",\"capped\":\"?\"}" ||
            it == "{\"create\": \"$collectionName\", \"capped\": \"?\", \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test create collection no description"() {
    setup:
    MongoDatabase db = MongoClients.create("mongodb://localhost:$port").getDatabase(dbName)

    when:
    db.createCollection(collectionName).subscribe(toSubscriber {})

    then:
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "create", {
          assert it.replaceAll(" ", "") == "{\"create\":\"$collectionName\",\"capped\":\"?\"}" ||
            it == "{\"create\": \"$collectionName\", \"capped\": \"?\", \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }, dbName)
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test get collection"() {
    setup:
    MongoDatabase db = client.getDatabase(dbName)

    when:
    def count = new CompletableFuture()
    db.getCollection(collectionName).estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })

    then:
    count.get() == 0
    assertTraces(1) {
      trace(1) {
        mongoSpan(it, 0, "count") {
          assert it.replaceAll(" ", "") == "{\"count\":\"$collectionName\",\"query\":{}}" ||
            it == "{\"count\": \"$collectionName\", \"query\": {}, \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test insert"() {
    setup:
    def collection = setupCollection(dbName, collectionName)

    when:
    def count = new CompletableFuture()
    insertDocument(collection, new Document("password", "SECRET"), toSubscriber {
      collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
    })

    then:
    count.get() == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "insert") {
          assert it.replaceAll(" ", "") == "{\"insert\":\"$collectionName\",\"ordered\":\"?\",\"documents\":[{\"_id\":\"?\",\"password\":\"?\"}]}" ||
            it == "{\"insert\": \"$collectionName\", \"ordered\": \"?\", \"\$db\": \"?\", \"documents\": [{\"_id\": \"?\", \"password\": \"?\"}]}"
          true
        }
      }
      trace(1) {
        mongoSpan(it, 0, "count") {
          assert it.replaceAll(" ", "") == "{\"count\":\"$collectionName\",\"query\":{}}" ||
            it == "{\"count\": \"$collectionName\", \"query\": {}, \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test update"() {
    setup:
    MongoCollection<Document> collection = setupCollection(dbName, collectionName)
    insertDocument(collection, new Document("password", "OLDPW"), null)

    when:
    def result = new CompletableFuture<UpdateResult>()
    def count = new CompletableFuture()
    collection.updateOne(
      new BsonDocument("password", new BsonString("OLDPW")),
      new BsonDocument('$set', new BsonDocument("password", new BsonString("NEWPW")))).subscribe(toSubscriber {
      result.complete(it)
      collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
    })

    then:
    result.get().modifiedCount == 1
    count.get() == 1
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "update") {
          assert it.replaceAll(" ", "") == "{\"update\":\"?\",\"ordered\":\"?\",\"updates\":[{\"q\":{\"password\":\"?\"},\"u\":{\"\$set\":{\"password\":\"?\"}}}]}" ||
            it == "{\"update\": \"?\", \"ordered\": \"?\", \"\$db\": \"?\", \"updates\": [{\"q\": {\"password\": \"?\"}, \"u\": {\"\$set\": {\"password\": \"?\"}}}]}"
          true
        }
      }
      trace(1) {
        mongoSpan(it, 0, "count") {
          assert it.replaceAll(" ", "") == "{\"count\":\"$collectionName\",\"query\":{}}" ||
            it == "{\"count\": \"$collectionName\", \"query\": {}, \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def "test delete"() {
    setup:
    MongoCollection<Document> collection = setupCollection(dbName, collectionName)
    insertDocument(collection, new Document("password", "SECRET"), null)

    when:
    def result = new CompletableFuture<DeleteResult>()
    def count = new CompletableFuture()
    collection.deleteOne(new BsonDocument("password", new BsonString("SECRET"))).subscribe(toSubscriber {
      result.complete(it)
      collection.estimatedDocumentCount().subscribe(toSubscriber { count.complete(it) })
    })

    then:
    result.get().deletedCount == 1
    count.get() == 0
    assertTraces(2) {
      trace(1) {
        mongoSpan(it, 0, "delete") {
          assert it.replaceAll(" ", "") == "{\"delete\":\"?\",\"ordered\":\"?\",\"deletes\":[{\"q\":{\"password\":\"?\"},\"limit\":\"?\"}]}" ||
            it == "{\"delete\": \"?\", \"ordered\": \"?\", \"\$db\": \"?\", \"deletes\": [{\"q\": {\"password\": \"?\"}, \"limit\": \"?\"}]}"
          true
        }
      }
      trace(1) {
        mongoSpan(it, 0, "count") {
          assert it.replaceAll(" ", "") == "{\"count\":\"$collectionName\",\"query\":{}}" ||
            it == "{\"count\": \"$collectionName\", \"query\": {}, \"\$db\": \"?\", \"\$readPreference\": {\"mode\": \"?\"}}"
          true
        }
      }
    }

    where:
    dbName = "test_db"
    collectionName = "testCollection"
  }

  def Subscriber<?> toSubscriber(Closure closure) {
    return new Subscriber() {
      boolean hasResult

      @Override
      void onSubscribe(Subscription s) {
        s.request(1) // must request 1 value to trigger async call
      }

      @Override
      void onNext(Object o) { hasResult = true; closure.call(o) }

      @Override
      void onError(Throwable t) { hasResult = true; closure.call(t) }

      @Override
      void onComplete() {
        if (!hasResult) {
          hasResult = true
          closure.call()
        }
      }
    }
  }

  def mongoSpan(TraceAssert trace, int index, String operation, Closure<Boolean> statementEval, String instance = "some-instance", Object parentSpan = null, Throwable exception = null) {
    trace.span {
      serviceName "mongo"
      operationName "mongo.query"
      resourceName statementEval
      spanType DDSpanTypes.MONGO
      if (parentSpan == null) {
        parent()
      } else {
        childOf((DDSpan) parentSpan)
      }
      topLevel true
      tags {
        "$Tags.COMPONENT" "java-mongo"
        "$Tags.SPAN_KIND" Tags.SPAN_KIND_CLIENT
        "$Tags.PEER_HOSTNAME" "localhost"
        "$Tags.PEER_PORT" port
        "$Tags.DB_TYPE" "mongo"
        "$Tags.DB_INSTANCE" instance
        "$Tags.DB_OPERATION" operation
        defaultTags()
      }
    }
  }
}
