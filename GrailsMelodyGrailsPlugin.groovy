import grails.plugin.melody.GrailsMelodyUtil
import net.bull.javamelody.JdbcWrapper
import net.bull.javamelody.MonitoringFilter
import net.bull.javamelody.MonitoringProxy
import net.bull.javamelody.Parameter
import net.bull.javamelody.internal.common.Parameters
import net.bull.javamelody.SessionListener
import javax.sql.DataSource

class GrailsMelodyGrailsPlugin {

	def version = "1.86.0-2.5.x"
	def grailsVersion = "2.5.4 > *"

	def loadAfter = [
		'spring-security-core',
		'acegi',
		'shiro',
		'quartz'
	]

	def title = "JavaMelody Grails Plugin"
	def description = 'Integrate JavaMelody Monitoring into grails application.'
	def documentation = "http://grails.org/plugin/grails-melody"

	def license = "ASL"
	def organization = [ name: "JavaMelody", url: "https://github.com/javamelody/javamelody/wiki" ]
	def developers = [
			[ name: "Liu Chao", email: "liuchao@goal98.com" ],
			[ name: "Emeric Vernat", email: "evernat@free.fr" ] ]
	def issueManagement = [ system: "GitHub", url: "https://github.com/javamelody/grails-melody-plugin/issues" ]
	def scm = [ url: "https://github.com/javamelody/grails-melody-plugin.git" ]

	
	def getWebXmlFilterOrder() {
		def FilterManager = getClass().getClassLoader().loadClass('grails.plugin.webxml.FilterManager')
		[monitoring : FilterManager.GRAILS_WEB_REQUEST_POSITION + 200]
	}

	def doWithWebDescriptor = {xml ->

		def contextParam = xml.'context-param'

		contextParam[contextParam.size() - 1] + {
			//load configuration from GrailsMelodyConfig.groovy
			def conf = GrailsMelodyUtil.getGrailsMelodyConfig(application)?.javamelody
			conf?.each {
				String name = it.key
				String value = it.value
				log.debug "Grails Melody Param: $name = $value"
				'context-param' {
					'param-name'('javamelody.'+name)
					'param-value'(value)
				}
			}

			'filter' {
				'filter-name'('monitoring')
				'filter-class'(MonitoringFilter.name)
			}
		}

		findMappingLocation.delegate = delegate
		def mappingLocation = findMappingLocation(xml)
		mappingLocation + {
			'filter-mapping' {
				'filter-name'('monitoring')
				'url-pattern'('/*')
			}
		}


		def filterMapping = xml.'filter-mapping'
		filterMapping[filterMapping.size() - 1] + {
			'listener' { 'listener-class'(SessionListener.name) }
		}
	}

	private findMappingLocation = {xml ->

		// find the location to insert the filter-mapping; needs to be after the 'charEncodingFilter'
		// which may not exist. should also be before the sitemesh filter.
		// thanks to the JSecurity plugin for the logic.

		def mappingLocation = xml.'filter-mapping'.find { it.'filter-name'.text() == 'charEncodingFilter' }
		if (mappingLocation) {
			return mappingLocation
		}

		// no 'charEncodingFilter'; try to put it before sitemesh
		int i = 0
		int siteMeshIndex = -1
		xml.'filter-mapping'.each {
			if (it.'filter-name'.text().equalsIgnoreCase('sitemesh')) {
				siteMeshIndex = i
			}
			i++
		}
		if (siteMeshIndex > 0) {
			return xml.'filter-mapping'[siteMeshIndex - 1]
		}

		if (siteMeshIndex == 0 || xml.'filter-mapping'.size() == 0) {
			def filters = xml.'filter'
			return filters[filters.size() - 1]
		}

		// neither filter found
		def filters = xml.'filter'
		return filters[filters.size() - 1]
	}

	def doWithApplicationContext = { ctx ->
		//Need to wrap the datasources here, because BeanPostProcessor didn't worked.
		def beans = ctx.getBeansOfType(DataSource)
		beans.each { beanName, bean ->
			if(bean?.hasProperty("targetDataSource")) {
				bean.targetDataSource = JdbcWrapper.SINGLETON.createDataSourceProxy(bean.targetDataSource)
			}
		}
	}
	
	def doWithDynamicMethods = {ctx ->
		//For each service class in Grails, the plugin use groovy meta programming (invokeMethod)
		//to 'intercept' method call and collect infomation for monitoring purpose.
		//The code below mimics 'MonitoringSpringInterceptor.invoke()'
		def SPRING_COUNTER = MonitoringProxy.getSpringCounter()
		final boolean DISABLED = GrailsMelodyUtil.getGrailsMelodyConfig(application)?.javamelody?.disabled || Parameter.DISABLED.getValueAsBoolean()

		if (DISABLED || Parameters.isCounterHidden(SPRING_COUNTER.getName())) {
			if (DISABLED) {
				log.debug("Melody is disabled, services will not be enhanced.")
			} else {
				log.debug("Spring counter is not displayed, services will not be enhanced.")
			}
			return
		}


		//Enable groovy meta programming
		ExpandoMetaClass.enableGlobally()

		application.serviceClasses.each {serviceArtifactClass ->
			def serviceClass = serviceArtifactClass.getClazz()

			serviceClass.metaClass.invokeMethod = {String name, args ->
				def metaMethod = delegate.metaClass.getMetaMethod(name, args)
				if (!metaMethod) {
					List methods = delegate.metaClass.getMethods()
					boolean found = false
					for (MetaMethod method in methods) {
                  if (method.getName() == name) {
                     def parameterTypes = method.nativeParameterTypes
                     if(parameterTypes.length == args.length) {
                        found = true
                        for(int i = 0; i < parameterTypes.length; i++) {
                           if((args[i] != null && !parameterTypes[i].isAssignableFrom(args[i].getClass())) || (parameterTypes[i].primitive && args[i] == null)) {
                              found = false
                              break
                           }
                        }
                        if(found) {
                           metaMethod = method
                           break
                        }
                     }
                  }
					}
					if(!found && delegate.metaClass.hasProperty(delegate, name)){
						def property = delegate."${name}"
						if(property instanceof Closure){
							found = true
							metaMethod = [doMethodInvoke: {dlg, arguments-> property.call(arguments)}]
						}
					}
					if (!found){
						return delegate.metaClass.invokeMissingMethod(delegate, name, args)
						/*						throw new MissingMethodException(name, delegate.class, args)*/
					}
				}

				if (DISABLED || !SPRING_COUNTER.isDisplayed()) {
					return metaMethod.doMethodInvoke(delegate, args)
				}

				final String requestName = "${serviceClass.name}.${name}"

				boolean systemError = false
				try {
					SPRING_COUNTER.bindContextIncludingCpu(requestName)
					return metaMethod.doMethodInvoke(delegate, args)
				} catch (final Error e) {
					systemError = true
					throw e
				} finally {
					SPRING_COUNTER.addRequestForCurrentContext(systemError)
				}
			}
		}
	}
}
