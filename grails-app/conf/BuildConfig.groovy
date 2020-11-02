grails.project.work.dir = 'target'

rails.project.dependency.resolver = "maven"

grails.project.repos.seGrailsPlugins.url = "https://api.bintray.com/maven/secretescapes/grails-plugins/grails-melody-plugin/;publish=1"
grails.project.repos.default = "seGrailsPlugins"
grails.release.scm.enabled = false

grails.project.repos.seGrailsPlugins.username = System.getProperty("bintray.user")
grails.project.repos.seGrailsPlugins.password = System.getProperty("bintray.secret")

grails.project.dependency.resolution = {

    inherits 'global'
    log 'warn'

    repositories {
       	grailsCentral()
       	mavenLocal()
       // mavenCentral()
	mavenRepo "https://repo1.maven.org/maven2/"

    }

    dependencies {
        compile "net.bull.javamelody:javamelody-core:1.86.0"
        compile ("com.lowagie:itext:2.1.7") {excludes "bcmail-jdk14", "bcprov-jdk14", "bctsp-jdk14"}
        compile "org.jrobin:jrobin:1.5.9"
	compile "commons-io:commons-io:2.5"
    }

    plugins {
        build ':release:2.2.1', ':rest-client-builder:1.0.3', {
            export = false
        }
    }
}
