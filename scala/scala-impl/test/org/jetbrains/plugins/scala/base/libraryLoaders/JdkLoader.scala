package org.jetbrains.plugins.scala.base.libraryLoaders

import java.io.File

import com.intellij.openapi.module.Module
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.projectRoots.{JavaSdk, Sdk}
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import org.jetbrains.plugins.scala.base.libraryLoaders.JdkVersion.JdkVersion
import org.jetbrains.plugins.scala.debugger.ScalaVersion
import org.jetbrains.plugins.scala.extensions.inWriteAction
import org.junit.Assert

case class JdkLoader(jdkVersion: JdkVersion = JdkVersion.JDK18) extends LibraryLoader {

  override def init(implicit module: Module, version: ScalaVersion): Unit = {
    val jdk = JdkLoader.getOrCreateJDK(jdkVersion)
    ModuleRootModificationUtil.setModuleSdk(module, jdk)
    Disposer.register(module.getProject, () => inWriteAction {
      JavaAwareProjectJdkTableImpl.getInstanceEx.removeJdk(jdk)
    })
  }

}

object JdkLoader {

  val candidates = Seq(
    "/usr/lib/jvm",                     // linux style
    "C:\\Program Files\\Java\\",        // windows style
    "C:\\Program Files (x86)\\Java\\",  // windows 32bit style
    "/Library/Java/JavaVirtualMachines" // mac style
  )

  def getOrCreateJDK(jdkVersion: JdkVersion = JdkVersion.JDK18): Sdk = {
    val jdkTable = JavaAwareProjectJdkTableImpl.getInstanceEx
    val jdkName = jdkVersion.toString
    Option(jdkTable.findJdk(jdkName)).getOrElse {
      val pathOption = JdkLoader.discoverJDK(jdkVersion)
      Assert.assertTrue(s"Couldn't find $jdkVersion", pathOption.isDefined)
      VfsRootAccess.allowRootAccess(pathOption.get)
      val jdk = JavaSdk.getInstance.createJdk(jdkName, pathOption.get)
      inWriteAction {
        jdkTable.addJdk(jdk)
      }
      jdk
    }
  }

  def discoverJDK(jdkVersion: JdkVersion): Option[String] = discoverJre(candidates, jdkVersion).map(new File(_).getParent)

  def discoverJre(paths: Seq[String], jdkVersion: JdkVersion): Option[String] = {
    val versionMajor: String = jdkVersion.toString.last.toString
    import java.io._
    def isJDK(f: File) = f.listFiles().exists { b =>
      b.getName == "bin" && b.listFiles().exists(x => x.getName == "javac.exe" || x.getName == "javac")
    }
    def inJvm(path: String, suffix: String) = {
      val postfix = if (path.startsWith("/Library")) "/Contents/Home" else ""  // mac workaround
      Option(new File(path))
        .filter(_.exists())
        .flatMap(_.listFiles()
          .sortBy(_.getName) // TODO somehow sort by release number to get the newest actually
          .reverse
          .find(f => f.getName.contains(suffix) && isJDK(new File(f, postfix)))
          .map(new File(_, s"$postfix/jre").getAbsolutePath)
        )
    }
    def currentJava() = {
      sys.props.get("java.version") match {
        case Some(v) if v.startsWith(s"1.$versionMajor") =>
          sys.props.get("java.home") match {
            case Some(path) if isJDK(new File(path).getParentFile) =>
              Some(path)
            case _ => None
          }
        case _ => None
      }
    }
    val versionStrings = Seq(s"1.$versionMajor", s"-$versionMajor")
    val priorityPaths = Seq(
      currentJava(),
      Option(sys.env.getOrElse(s"JDK_1${versionMajor}_x64",
        sys.env.getOrElse(s"JDK_1$versionMajor", null))
      ).map(_+"/jre")  // teamcity style
    )
    if (priorityPaths.exists(_.isDefined)) {
      priorityPaths.flatten.headOption
    } else {
      val fullSearchPaths = paths flatMap { p => versionStrings.map((p, _)) }
      for ((path, ver) <- fullSearchPaths) {
        inJvm(path, ver) match {
          case x@Some(p) => return x
          case _ => None
        }
      }
      None
    }
  }
}

object JdkVersion extends Enumeration {
  type JdkVersion = Value
  val JDK16, JDK17, JDK18, JDK19 = Value
}
