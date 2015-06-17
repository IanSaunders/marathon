package mesosphere.marathon.core.launchqueue.impl

import mesosphere.marathon.Protos.MarathonTask
import mesosphere.marathon.core.base.actors.ActorsModule
import mesosphere.marathon.core.base.{ Clock, ShutdownHooks }
import mesosphere.marathon.core.launchqueue.LaunchQueueModule
import mesosphere.marathon.core.matcher.DummyOfferMatcherManager
import mesosphere.marathon.core.task.bus.TaskBusModule
import mesosphere.marathon.state.PathId
import mesosphere.marathon.tasks.TaskFactory.CreatedTask
import mesosphere.marathon.tasks.{ TaskIdUtil, TaskFactory, TaskTracker }
import mesosphere.marathon.{ MarathonSpec, MarathonTestHelper }
import org.apache.mesos.Protos.TaskID
import org.mockito.Mockito.{ when => call, _ }
import org.scalatest.{ BeforeAndAfter, GivenWhenThen }
import scala.concurrent.Await
import scala.concurrent.duration._

class DefaultLaunchQueueModuleTest extends MarathonSpec with BeforeAndAfter with GivenWhenThen {

  // FIXME: Missing tests
  // - Timeout for launching
  // - Task tracking

  test("empty queue returns no results") {
    When("querying queue")
    val apps = taskQueue.list

    Then("no apps are returned")
    assert(apps.isEmpty)
  }

  test("An added queue item is returned in list") {
    Given("a task queue with one item")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)

    When("querying its contents")
    val list = taskQueue.list

    Then("we get back the added app")
    assert(list.size == 1)
    assert(list.head.app == app)
    assert(list.head.tasksLeftToLaunch == 1)
    assert(list.head.tasksLaunchedOrRunning == 0)
    assert(list.head.taskLaunchesInFlight == 0)
    verify(taskTracker).get(app.id)
  }

  test("An added queue item is reflected via count") {
    Given("a task queue with one item")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 1")
    assert(count == 1)
    verify(taskTracker).get(app.id)
  }

  test("A purged queue item has a count of 0") {
    Given("a task queue with one item which is purged")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)
    taskQueue.purge(app.id)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 0")
    assert(count == 0)
    verify(taskTracker).get(app.id)
  }

  test("A re-added queue item has a count of 1") {
    Given("a task queue with one item which is purged")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)
    taskQueue.purge(app.id)
    taskQueue.add(app)

    When("querying its count")
    val count = taskQueue.count(app.id)

    Then("we get a count == 1")
    assert(count == 1)
    verify(taskTracker, times(2)).get(app.id)
  }

  test("adding a queue item registers new offer matcher") {
    Given("An empty task tracker")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])

    When("Adding an app to the taskQueue")
    taskQueue.add(app)

    Then("A new offer matcher gets registered")
    assert(offerMatcherManager.offerMatchers.size == 1)
    verify(taskTracker).get(app.id)
  }

  test("puring a queue item UNregisters offer matcher") {
    Given("An app in the queue")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)

    When("The app is purged")
    taskQueue.purge(app.id)

    Then("No offer matchers remain registered")
    assert(offerMatcherManager.offerMatchers.isEmpty)
    verify(taskTracker).get(app.id)
  }

  test("an offer gets unsuccessfully matched against an item in the queue") {
    val offer = MarathonTestHelper.makeBasicOffer().build()

    Given("An app in the queue")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    taskQueue.add(app)

    When("we ask for matching an offer but no tasks can be launched")
    call(taskFactory.newTask(app, offer, Set.empty[MarathonTask])).thenReturn(None)
    val matchFuture = offerMatcherManager.offerMatchers.head.processOffer(clock.now() + 3.seconds, offer)
    val matchedTasks = Await.result(matchFuture, 3.seconds)

    Then("the offer gets passed to the task factory and respects the answer")
    verify(taskFactory).newTask(app, offer, Set.empty[MarathonTask])
    assert(matchedTasks.offerId == offer.getId)
    assert(matchedTasks.tasks == Seq.empty)

    verify(taskTracker).get(app.id)
  }

  test("an offer gets successfully matched against an item in the queue") {
    val offer = MarathonTestHelper.makeBasicOffer().build()
    val taskId: TaskID = TaskIdUtil.newTaskId(app.id)
    val mesosTask = MarathonTestHelper.makeOneCPUTask("").setTaskId(taskId).build()
    val marathonTask = MarathonTask.newBuilder().setId(taskId.getValue).build()
    val createdTask = CreatedTask(mesosTask, marathonTask)

    Given("An app in the queue")
    call(taskTracker.get(app.id)).thenReturn(Set.empty[MarathonTask])
    call(taskFactory.newTask(app, offer, Set.empty[MarathonTask])).thenReturn(Some(createdTask))
    taskQueue.add(app)

    When("we ask for matching an offer but no tasks can be launched")
    val matchFuture = offerMatcherManager.offerMatchers.head.processOffer(clock.now() + 3.seconds, offer)
    val matchedTasks = Await.result(matchFuture, 3.seconds)

    Then("the offer gets passed to the task factory and respects the answer")
    verify(taskFactory).newTask(app, offer, Set.empty[MarathonTask])
    assert(matchedTasks.offerId == offer.getId)
    assert(matchedTasks.tasks == Seq(mesosTask))

    verify(taskTracker).get(app.id)
    verify(taskTracker).created(app.id, marathonTask)
  }

  private[this] val app = MarathonTestHelper.makeBasicApp().copy(id = PathId("/app"))

  private[this] var shutdownHooks: ShutdownHooks = _
  private[this] var clock: Clock = _
  private[this] var taskBusModule: TaskBusModule = _
  private[this] var offerMatcherManager: DummyOfferMatcherManager = _
  private[this] var taskTracker: TaskTracker = _
  private[this] var taskFactory: TaskFactory = _
  private[this] var module: LaunchQueueModule = _

  private[this] def taskQueue = module.taskQueue

  before {
    shutdownHooks = ShutdownHooks()
    val actorsModule = ActorsModule(shutdownHooks)
    clock = Clock()
    taskBusModule = TaskBusModule()

    offerMatcherManager = new DummyOfferMatcherManager()
    taskTracker = mock[TaskTracker]("taskTracker")
    taskFactory = mock[TaskFactory]("taskFactory")

    module = LaunchQueueModule(
      actorsModule.actorSystem,
      clock,
      subOfferMatcherManager = offerMatcherManager,
      taskStatusObservables = taskBusModule.taskStatusObservables,
      taskTracker,
      taskFactory
    )
  }

  after {
    verifyNoMoreInteractions(taskTracker)
    verifyNoMoreInteractions(taskFactory)

    shutdownHooks.shutdown()
  }
}