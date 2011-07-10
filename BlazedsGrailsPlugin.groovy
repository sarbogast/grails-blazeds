import flex.messaging.config.ConfigMap
import flex.messaging.io.PropertyProxyRegistry
import flex.messaging.services.RemotingService

import grails.plugin.blazeds.BlazeDsRemotingDestinationExporter
import grails.plugin.blazeds.BlazeDsUrlHandlerMapping

import grails.persistence.Event
import grails.util.GrailsNameUtils

import java.lang.reflect.Method

import org.apache.log4j.Logger

import org.codehaus.groovy.grails.commons.ConfigurationHolder as CH
import org.codehaus.groovy.grails.commons.GrailsServiceClass

import org.hibernate.EntityMode

import org.springframework.core.convert.converter.ConverterFactory
import org.springframework.core.convert.support.GenericConversionService
import org.springframework.flex.config.RemotingDestinationMetadata.FlexExcludeFilter
import org.springframework.flex.config.RemotingDestinationMetadata.FlexIncludeFilter
import org.springframework.flex.core.ExceptionTranslationAdvice
import org.springframework.flex.core.ManageableComponentFactoryBean
import org.springframework.flex.core.MessageInterceptionAdvice
import org.springframework.flex.core.io.HibernateProxyConverter
import org.springframework.flex.core.io.JpaHibernateConfigProcessor
import org.springframework.flex.core.io.PersistentCollectionConverterFactory
import org.springframework.flex.core.io.NumberConverter
import org.springframework.flex.remoting.RemotingDestination as RemotingDestinationAnnotation
import org.springframework.flex.remoting.RemotingExclude
import org.springframework.flex.remoting.RemotingInclude
import org.springframework.flex.security3.LoginMessageInterceptor
import org.springframework.flex.security3.SecurityExceptionTranslator
import org.springframework.flex.security3.SpringSecurityLoginCommand
import org.springframework.util.ReflectionUtils
import org.springframework.util.ReflectionUtils.MethodCallback
import org.springframework.util.ReflectionUtils.MethodFilter
import org.springframework.flex.core.io.SpringPropertyProxy
import java.beans.PropertyDescriptor
import org.springframework.flex.core.LoginCommandConfigProcessor
import org.springframework.security.authentication.AuthenticationManager

class BlazedsGrailsPlugin {
	def version = "2.0"
	def grailsVersion = "1.2.2 > *"
	def dependsOn = ['springSecurityAcl': '1.0 > *']
	def loadAfter = ['springSecurityAcl']
	def observe = ['services']
	def pluginExcludes = [
		"grails-app/views/error.gsp",
		"grails-app/views/login/**",
		"grails-app/domain/**",
		"grails-app/controllers/**",
		"grails-app/i18n/**",
		"grails-app/services/**",
		"web-app/css/**",
		"web-app/images/**",
		"web-app/js/**",
		'docs/**',
		'src/docs/**'
	]

	def author = "Sebastien Arbogast"
	def authorEmail = "sebastien.arbogast@gmail.com"
	def title = "Grails BlazeDS 4 Integration"
	def description = '''\\
Basic plugin to integrate BlazeDS 4 into Grails so that you can connect to a Grails backend with a Flex 4 frontend
'''

	def documentation = "http://grails.org/plugin/blazeds"

	private static Logger LOG = Logger.getLogger('grails.plugin.BlazedsGrailsPlugin')

	def doWithWebDescriptor = { xml ->

		// listeners
		def listeners = xml.listener
		listeners[listeners.size() - 1] + {
			listener {
				'listener-class'('flex.messaging.HttpFlexSession')
			}
		}

		// servlets
		def servlets = xml.servlet
		servlets[servlets.size() - 1] + {
			servlet {
				'servlet-name'("RDSDispatchServlet")
				'display-name'("Flash Builder wizard helper")
				'servlet-class'("flex.rds.server.servlet.FrontEndServlet")
				'init-param' {
					'param-name'("messageBrokerId")
					'param-value'("_messageBroker")
				}
				'init-param' {
					'param-name'("useAppserverSecurity")
					'param-value'("false")
				}
				'load-on-startup'("10")
			}
		}

		// servlet mappings
		def servletMappings = xml.'servlet-mapping'
		servletMappings[servletMappings.size() - 1] + {
			'servlet-mapping'(id: "RDS_DISPATCH_MAPPING") {
				'servlet-name'("RDSDispatchServlet")
				'url-pattern'("/CFIDE/main/ide.cfm")
			}
			'servlet-mapping' {
				'servlet-name'("grails")
				'url-pattern'("/messagebroker/*")
			}
		}

		if (manager?.hasGrailsPlugin('hibernate')) {
			if (blazedsConfig.disableOpenSessionInView instanceof Boolean && blazedsConfig.disableOpenSessionInView) {
				return
			}

			// we add the filter right after the last context-param
			def contextParam = xml.'context-param'
			contextParam[contextParam.size() - 1] + {
				'filter' {
					'filter-name'('blazedsOpenSessionInViewFilter')
					'filter-class'('grails.plugin.blazeds.BlazedsOpenSessionInViewFilter')
				}
			}

			def filterMappings = xml.'filter-mapping'
			def lastFilterMapping = filterMappings[filterMappings.size() - 1]
			lastFilterMapping + {
				'filter-mapping'{
					'filter-name'('blazedsOpenSessionInViewFilter')
					'url-pattern'('/messagebroker/*')
				}
			}
		}
	}

	def doWithSpring = {

		xmlns flex:'http://www.springframework.org/schema/flex'

		configureSecurity.delegate = delegate
		configureSecurity()

		configureHibernate.delegate = delegate
		configureHibernate()

		configureMessageBroker.delegate = delegate
		configureMessageBroker()

		createRemotingDestinations.delegate = delegate
		createRemotingDestinations()
	}

	def doWithApplicationContext = { ctx ->
		initInterceptors ctx
		initConverters ctx
		initDomainClassProxies ctx
	}

	def onChange = { event ->
		if (!event.source || !application.isServiceClass(event.source)) {
			return
		}

		def serviceClass = event.source
		def broker = event.ctx.getBean('_messageBroker')
		def remotingService = broker.getServiceByType(RemotingService.name)

		String beanName = GrailsNameUtils.getPropertyNameRepresentation(serviceClass.simpleName)

		remotingService.removeDestination(beanName) // TODO might have used a dest id in the annotation

		def annotation = serviceClass.getAnnotation(RemotingDestinationAnnotation)
		if (annotation) {
			String destinationId = annotation.value() ?: beanName
			List<String[]> excludesAndIncludes = extractExcludeAndIncludeMethods(serviceClass)
			def exporter = new BlazeDsRemotingDestinationExporter(
				messageBroker: broker,
				serviceBeanName: beanName,
				serviceClass: serviceClass,
				destinationId: destinationId,
				channels: annotation.channels(),
				serviceAdapter: annotation.serviceAdapter(),
				excludeMethods: excludesAndIncludes[0],
				includeMethods: excludesAndIncludes[1],
				beanFactory: event.ctx.beanFactory)
			exporter.afterPropertiesSet()
		}
	}

	private configureSecurity = {

		//blazeDsMessageBrokerLoginCommand(LoginCommandConfigProcessor(SpringSecurityLoginCommand, ref('authenticationManager')) )


        mySpringSecurityLoginCommand(SpringSecurityLoginCommand, ref('authenticationManager') );

        blazeDsMessageBrokerLoginCommand(LoginCommandConfigProcessor, ref('mySpringSecurityLoginCommand') )

		blazeDsSecurityExceptionTranslator(SecurityExceptionTranslator)

		blazeDsLoginMessageInterceptor(LoginMessageInterceptor)
	}

	private configureHibernate = {
		blazeDsConversionService(GenericConversionService)

		blazeDsHibernateConfigProcessor(JpaHibernateConfigProcessor) {
			conversionService = ref('blazeDsConversionService')
		}
	}

	private configureMessageBroker = {

		flex.'message-broker'('services-config-path': '/WEB-INF/flex/services-config.xml',
		                      'disable-default-mapping': true) {
			String defaultMessageChannels = getBlazedsConfig().defaultMessageChannels ?: ''
			if (defaultMessageChannels) {
				'message-service'('default-channels': defaultMessageChannels)
				debug "default message service channels: $defaultMessageChannels"
			}
			String defaultRemoteChannels = getBlazedsConfig().defaultRemoteChannels ?: ''
			if (defaultRemoteChannels) {
				'remoting-service'('default-channels': defaultRemoteChannels)
				debug "default remoting service channels: $defaultRemoteChannels"
			}
            'config-processor'(ref: 'blazeDsMessageBrokerLoginCommand')
			'config-processor'(ref: 'blazeDsHibernateConfigProcessor')
		}

		// replaces the one that would be created by Spring-Flex but is Grails-aware
		flexHandlerMapping(BlazeDsUrlHandlerMapping) {
			mappings = '/*=_messageBroker'
		}
	}

	private createRemotingDestinations = {

		// if we create a bean with this name Spring-Flex won't register a RemotingAnnotationPostProcessor which
		// finds annotated destinations in 1.2 but not in 1.3; we find them all in either case so it's not needed
		'_flexRemotingAnnotationPostProcessor'(Object)

		createRemotingDestination.delegate = delegate

		for (GrailsServiceClass serviceClass in application.serviceClasses) {
			Class clazz = serviceClass.clazz
			RemotingDestinationAnnotation annotation = clazz.getAnnotation(RemotingDestinationAnnotation)
			if (!annotation) continue

			String beanName = serviceClass.propertyName
			String destinationId = annotation.value() ?: beanName
			List<String[]> excludesAndIncludes = extractExcludeAndIncludeMethods(clazz)

			createRemotingDestination(clazz, beanName, destinationId, annotation.channels(),
				annotation.serviceAdapter(), excludesAndIncludes[1], excludesAndIncludes[0])
		}
	}

	private createRemotingDestination = { Class serviceClazz, String beanName, String remotingDestination,
	                                      String[] channelNames, String serviceAdapterName,
	                                      String[] includes, String[] excludes ->

		debug "creating remoting destination: beanName: $beanName remotingDestination $remotingDestination excludes $excludes includes $includes"

		"${beanName}FlexRemote"(BlazeDsRemotingDestinationExporter) { bean ->
			bean.dependsOn = [beanName] as String[]
			messageBroker = ref('_messageBroker')
			serviceBeanName = beanName
			serviceClass = serviceClazz
			destinationId = remotingDestination
			channels = channelNames
			serviceAdapter = serviceAdapterName
			excludeMethods = excludes
			includeMethods = includes
		}
	}

	private void initInterceptors(ctx) {

		// add the LoginMessageInterceptor to the interceptors; make a copy of the set in case it's immutable
		def messageInterceptionAdvice = ctx.getBeansOfType(MessageInterceptionAdvice).values().iterator().next()
		Set messageInterceptors = []
		messageInterceptors.addAll messageInterceptionAdvice.messageInterceptors
		messageInterceptors << ctx.blazeDsLoginMessageInterceptor
		messageInterceptionAdvice.messageInterceptors = messageInterceptors

		// add the SecurityExceptionTranslator to the translators; make a copy of the set in case it's immutable
		def exceptionTranslationAdvice = ctx.getBeansOfType(ExceptionTranslationAdvice).values().iterator().next()
		Set exceptionTranslators = []
		exceptionTranslators.addAll exceptionTranslationAdvice.exceptionTranslators
		exceptionTranslators << ctx.blazeDsSecurityExceptionTranslator
		exceptionTranslationAdvice.exceptionTranslators = exceptionTranslators
	}

	private void initConverters(ctx) {
		def conversionService = ctx.blazeDsConversionService

		def converterNames = [HibernateProxyConverter.name,
		                      PersistentCollectionConverterFactory.name,
		                      NumberConverter.name]
		if (blazedsConfig.converterNames instanceof List) {
			converterNames = blazedsConfig.converterNames
		}

		for (name in converterNames) {
			def converter = Class.forName(name , true, Thread.currentThread().contextClassLoader).newInstance()
			if (converter instanceof ConverterFactory) {
				conversionService.addConverterFactory converter
				debug "added converter factory $converter"
			}
			else {
				conversionService.addConverter converter
				debug "added converter $converter"
			}
		}
	}

	private void initDomainClassProxies(ctx) {
		def conversionService = ctx.blazeDsConversionService

		def ignoreNames = Event.allEvents.toList() << 'class' << 'metaClass' << 'hibernateLazyInitializer'
		if (blazedsConfig.proxyIgnoreProperties instanceof List) {
			ignoreNames = blazedsConfig.proxyIgnoreProperties
			debug "configuring domain class proxies to ignore $ignoreNames"
		}

		// register a proxy for all domain classes to limit auto-serialization of metaclass, etc.
		for (classMetadata in ctx.sessionFactory.allClassMetadata.values()) {

            for(String name: ignoreNames) {
                SpringPropertyProxy.addIgnoreProperty(classMetadata.getMappedClass(EntityMode.POJO), name);
            }

            def proxy = SpringPropertyProxy.proxyFor(classMetadata.getMappedClass(EntityMode.POJO), false, conversionService);
			debug "created domain class proxy for ${classMetadata.getMappedClass(EntityMode.POJO)}"

			PropertyProxyRegistry.registry.register proxy.beanType, proxy
		}
	}

	private getBlazedsConfig() { CH.config.grails.plugin.blazeds }

	private List<String[]> extractExcludeAndIncludeMethods(Class<?> serviceClass) {
		Set<String> excludes = []
		def excludeFilter = [matches: { Method m -> m.getAnnotation(RemotingExclude) != null }] as MethodFilter
		def excludeMethodCallback = [doWith: { Method m -> excludes << m.name }] as MethodCallback
		ReflectionUtils.doWithMethods(serviceClass, excludeMethodCallback, excludeFilter)

		Set<String> includes = []
		def includeFilter = [matches: { Method m -> m.getAnnotation(RemotingInclude) != null }] as MethodFilter
		def includeMethodCallback = [doWith: { Method m -> includes << m.name }] as MethodCallback
		ReflectionUtils.doWithMethods(serviceClass, includeMethodCallback, includeFilter)

		[excludes as String[], includes as String[]]
	}

	private void debug(message) {
		LOG.debug message
	}
}
