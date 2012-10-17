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
  val data = Array.ofDim[Boolean](1000,1000)
  val wikiTextFactory: ActorRef = sys.actorOf(Props[WikiTextFactory], name = "wiki")
  implicit val timeout:Timeout = Timeout(5 seconds)
  val rnd = new Random()
  val range = 0 to 999
  var wikiTexts:List[WikiText] = Nil

  override def setup() {
    size(1000, 1000)
    val tempFile: File = Files.createTempDir()
    tempFile.mkdirs()
    val grabber: ActorRef = sys.actorOf(Props[WikiGrabber].withRouter(BroadcastRouter(5)), name = "grabber")
    val files: ActorRef = sys.actorOf(Props(new FileSaver(tempFile,grid,grabber,wikiTextFactory)), name = "files")
    files ! Start
  }

  override def draw() {
    background(255)
    val result = Await.result(grid ? GetGrid, 1 second).asInstanceOf[Array[Array[Boolean]]]
    wikiTexts = wikiTexts ::: Await.result(wikiTextFactory ? GetTexts, 1 second).asInstanceOf[List[WikiText]]
    wikiTexts = wikiTexts.filter {t => !t.complete()}
    fill(0)
    range.foreach{row => range.foreach{col => if(result(row)(col)){point(row,col)}} }
    wikiTexts.foreach {t => t.display(this)}
    wikiTexts = wikiTexts.map {t => t.next()}
  }
}

sealed trait Message
case class Update(x:Int, y : Int, occupied:Boolean) extends Message
case class NewFile(text:String) extends Message
case object Changes extends Message
case object GetRand extends Message
case object Start extends Message
case object GetGrid extends Message
case object GetTexts extends Message

class Grid extends Actor{
  val data = Array.ofDim[Boolean](1000,1000)

  protected def receive = {
    case up:Update ⇒
      data(up.x)(up.y) = up.occupied
    case GetGrid ⇒
      sender ! data
  }
}

class WikiTextFactory extends Actor{
  var texts : List[String] = Nil
  val rnd = new Random()
  val range = 0 to 999

  def receive = {
    case NewFile(text) ⇒
      texts = (text :: texts).take(10)
    case GetTexts ⇒
      sender ! texts.map {t => new WikiText(range(rnd.nextInt(range length)),range(rnd.nextInt(range length)),t,1) }
      texts = Nil
  }
}

class WikiText(val x:Int, val y:Long, val text:String, val frame:Int) {
   val tint = 255 - ((255/100) * frame)

   def display(context:PApplet) {
     context.fill(0,0,0,tint)
     context.text(text,x,y)
     context.fill(0)
   }

   def complete() = {
      frame == 100
   }

   def next() = {
      new WikiText(x,y,text,frame + 1)
   }
}

class FileSaver(dir:File, listener:ActorRef, grabber:ActorRef, wikiTextFactory:ActorRef) extends Actor{
  val rnd = new Random()
  val range = 0 to 999
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
  }


}

class WikiGrabber() extends Actor{
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
      range.foreach {i => sender ! NewFile(extracts.item(i).getTextContent)}
  }
}

