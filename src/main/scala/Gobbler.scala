import akka.dispatch.Await
import akka.routing.BroadcastRouter
import akka.util.Timeout
import collection.immutable.Range.Inclusive
import java.io.{BufferedWriter, FileWriter, File}
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.{XPathConstants, XPathFactory}
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.DefaultHttpClient
import org.w3c.dom.NodeList
import processing.core.PApplet
import akka.actor.{ActorRef, Props, ActorSystem, Actor}
import akka.pattern.ask
import akka.util.duration._
import util.Random
import com.google.common.io.Files

object Gobbler extends App {
  PApplet.main(Array("--present", "GobblerSketch" ))
}

class GobblerSketch extends PApplet {

  val sys: ActorSystem = ActorSystem.create("gobbler")
  val grid: ActorRef = sys.actorOf(Props[Grid], name = "grid")
  val data = Array.ofDim[Boolean](500,500)
  val wikiTextFactory: ActorRef = sys.actorOf(Props[WikiTextFactory], name = "wiki")
  val actionLog:ActorRef = sys.actorOf(Props[ActionLog],name = "actionLog")
  implicit val timeout:Timeout = Timeout(5 seconds)
  val rnd = new Random()
  val range = 0 to 499
  var wikiTexts:List[WikiText] = Nil
  var logMessages:List[String] = Nil

  override def setup() {
    size(1000, 1000)
    val tempFile: File = Files.createTempDir()
    tempFile.mkdirs()
    val fiddler: ActorRef = sys.actorOf(Props(new FileFiddler(tempFile, grid, actionLog)), name = "fiddler")
    val grabber: ActorRef = sys.actorOf(Props(new WikiGrabber(fiddler)).withRouter(BroadcastRouter(5)), name = "grabber")
    val files: ActorRef = sys.actorOf(Props(new FileSaver(tempFile,grid,grabber,wikiTextFactory, actionLog)), name = "files")
    files ! Start
  }

  override def draw() {
    background(255)
    drawGrid()
    drawWikiQuotes()
    drawLogMessages()
  }


  def drawLogMessages() {
    (1 to logMessages.length).foreach {
      i => text(logMessages(i - 1), 10, i * 10)
    }
    val logs = Await.result(actionLog ? GetTexts, 1 second).asInstanceOf[List[String]]
    logMessages = (logs ::: logMessages).take(100)
  }

  def drawWikiQuotes() {
    wikiTexts = wikiTexts.filter {
      t => !t.complete()
    }
    wikiTexts = (wikiTexts ::: Await.result(wikiTextFactory ? GetTexts, 1 second).asInstanceOf[List[WikiText]]).take(8)
    wikiTexts.foreach {
      t => t.display(this)
    }
    wikiTexts = wikiTexts.map {
      t => t.next()
    }
  }

  def drawGrid (){
    val result = Await.result(grid ? GetGrid, 1 second).asInstanceOf[Array[Array[Boolean]]]
    range.foreach {
      row =>
        range.foreach {
          col =>
            if (result(row)(col)) {
              stroke(255 * row / 500, 255 * col / 500, 0, 75)
              fill(255 * row / 500, 255 * col / 500, 0, 75)
              rect((row * 2) - 1, (col * 2) - 1, 2, 2)
            }
        }
    }
    fill(0)
  }
}

sealed trait Message
case class Update(x:Int, y : Int, occupied:Boolean) extends Message
case class Deleted(x:Int, y : Int) extends Message
case class Moved(fromX:Int, fromY : Int,toX:Int,toY:Int) extends Message
case class NewFile(text:String) extends Message
case class LogMessage(text:String) extends Message
case object Changes extends Message
case object GetRand extends Message
case object Start extends Message
case object GetGrid extends Message
case object GetTexts extends Message
case object Move extends Message
case class Occupied(x:Int, y:Int) extends Message

class Grid extends Actor{
  val data = Array.ofDim[Boolean](500,500)
  var occupied : Set[Occupied] = Set() 
  val rnd = new Random()

  protected def receive = {
    case up:Update ⇒
      data(up.x)(up.y) = up.occupied
      if(up.occupied){occupied = occupied + new Occupied(up.x,up.y)}
    case Deleted(x,y) ⇒
      data(x)(y) = false
      val occ = new Occupied(x,y)
      occupied = occupied.filter {x => x != occ}
    case Moved(fromx,fromy,tox,toy) ⇒
      data(fromx)(fromy) = false
      val occ = new Occupied(fromx,fromy)
      occupied = occupied.filter {x => x != occ}
      data(tox)(toy) = true
      occupied = occupied + new Occupied(tox,toy) 
    case GetGrid ⇒
      sender ! data
    case GetRand ⇒
      val list = occupied.toList
      if(list.length > 0){
      	sender ! list(rnd.nextInt(list.length))
      }else{
        sender ! new Occupied(1,1)
      }
  }
}

class WikiTextFactory extends Actor{
  var texts : List[String] = Nil
  val rnd = new Random()
  val xrange = 0 to 200
  val yrange = 0 to 499

  def receive = {
    case NewFile(text) ⇒
      texts = (text :: texts).take(3)
    case GetTexts ⇒
      sender ! texts.map {t => new WikiText(xrange(rnd.nextInt(xrange length)) * 2,yrange(rnd.nextInt(yrange length)) * 2,t,1) }
      texts = Nil
  }
}

class ActionLog extends Actor{
  var texts : List[String] = Nil
  def receive = {
    case LogMessage(message) ⇒
      texts = (message :: texts).take(100)
    case GetTexts ⇒
      sender ! texts
      texts = Nil
  }
}

class WikiText(val x:Int, val y:Long, val text:String, val frame:Int) {
   val tint = 255 - ((255/150) * frame)

   def display(context:PApplet) {
     context.fill(0,0,0,tint)
     context.text(text,x,y)
     context.fill(0)
   }

   def complete() = {
      frame == 150
   }

   def next() = {
      new WikiText(x,y,text,frame + 1)
   }
}

class FileSaver(dir:File, listener:ActorRef, grabber:ActorRef, wikiTextFactory:ActorRef, actionLog: ActorRef) extends Actor{
  val rnd = new Random()
  val range = 0 to 499
  val temp = dir

  def receive = {
    case Start ⇒
      grabber ! GetRand
    case n:NewFile ⇒
      val x = range(rnd.nextInt(range length))
      val y = range(rnd.nextInt(range length))
      val f = new File(temp,x.toString + y.toString)
      f.createNewFile()
      val writer = new BufferedWriter( new FileWriter( f))
      writer.write( n.text)
      writer.close()
      listener ! Update(x,y,occupied = true)
      wikiTextFactory ! n
      grabber ! GetRand
      actionLog ! LogMessage("created " + x + y)
  }

}

class FileFiddler(dir:File, grid:ActorRef, actionLog: ActorRef) extends Actor{
  val rnd = new Random()
  val range = 0 to 499
  val temp = dir
  implicit val timeout:Timeout = Timeout(5 seconds)
  
  def receive = {
    case Move ⇒
      val result = Await.result(grid ? GetRand, 1 second).asInstanceOf[Occupied]
      val fromx = result.x 
      val fromy = result.y 
      val from = new File(dir,fromx.toString+fromy.toString)
      if(from.exists()){
        if (rnd.nextInt(10) > 2){
          val tox = range(rnd.nextInt(range length))
          val toy = range(rnd.nextInt(range length))
          if(tox != fromx && toy != fromy){
            val to = new File(dir,tox.toString+toy.toString)
            Files.move(from,to)
            grid ! Moved(fromx, fromy,tox,toy)
            actionLog ! LogMessage("moved " + fromx + fromy + " to " + tox + toy)
          }
        }else{
          from.delete()
          grid ! Deleted(fromx, fromy)
          actionLog ! LogMessage("deleted " + fromx + fromy)
        }
      }
  }
}

class WikiGrabber(fiddler:ActorRef) extends Actor{
  val httpclient = new DefaultHttpClient()
  val httpGet = new HttpGet("http://en.wikipedia.org/w/api.php?action=query&generator=random&grnlimit=20&grnnamespace=0&prop=extracts&explaintext&exintro&exsectionformat=plain&exlimit=20&format=xml")
  val factory = DocumentBuilderFactory.newInstance()
  factory.setNamespaceAware(true) // never forget this!
  val builder = factory.newDocumentBuilder()
  val xpathFactory = XPathFactory.newInstance()
  val xpath = xpathFactory.newXPath()
  val expr = xpath.compile("//extract")

  protected def receive = {
    case GetRand ⇒
      val response1 = httpclient.execute(httpGet)
      val doc = builder.parse(response1.getEntity.getContent)
      val extracts: NodeList = expr.evaluate(doc, XPathConstants.NODESET).asInstanceOf[NodeList]
      val range: Inclusive = 0 to extracts.getLength - 1
      range.foreach {i => sender ! NewFile(extracts.item(i).getTextContent);fiddler ! Move}

  }
}

