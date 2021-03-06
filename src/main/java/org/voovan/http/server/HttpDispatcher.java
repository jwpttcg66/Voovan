package org.voovan.http.server;

import org.voovan.http.message.packet.Cookie;
import org.voovan.http.server.context.HttpFilterConfig;
import org.voovan.http.server.context.WebContext;
import org.voovan.http.server.context.WebServerConfig;
import org.voovan.http.server.exception.ResourceNotFound;
import org.voovan.http.server.exception.RouterNotFound;
import org.voovan.http.server.router.MimeFileRouter;
import org.voovan.tools.*;
import org.voovan.tools.log.Logger;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.*;

/**
 * 
 * 根据 Request 请求分派到处理路由
 * 
 * 
 * GET 请求获取Request-URI所标识的资源
 * POST 在Request-URI所标识的资源后附加新的数据
 * HEAD 请求获取由Request-URI所标识的资源的响应消息报头
 * PUT 请求服务器存储一个资源，并用Request-URI作为其标识
 * DELETE 请求服务器删除Request-URI所标识的资源
 * TRACE 请求服务器回送收到的请求信息，主要用于测试或诊断
 * CONNECT 保留将来使用
 * OPTIONS 请求查询服务器的性能，或者查询与资源相关的选项和需求
 * 
 * @author helyho
 *
 * Voovan Framework.
 * WebSite: https://github.com/helyho/Voovan
 * Licence: Apache v2 License
 */
public class HttpDispatcher {
	/**
	 * [MainKey] = HTTP method ,[Value] = { [Value Key] = Route path, [Value value] = RouteBuiz对象 }
	 */
	private Map<String, Map<String, HttpRouter>> methodRouters;
	private WebServerConfig webConfig;
	private SessionManager sessionManager;
	private MimeFileRouter mimeFileRouter;
	private String[] indexFiles;

	/**
	 * 构造函数
	 * 
	 * @param webConfig    Web 服务配置对象
	 * @param sessionManager Session 管理器
	 */
	public HttpDispatcher(WebServerConfig webConfig, SessionManager sessionManager) {
		methodRouters = new LinkedHashMap<String, Map<String, HttpRouter>>();
		this.webConfig = webConfig;
		this.sessionManager = sessionManager;

		//拆分首页索引文件的名称
        indexFiles = webConfig.getIndexFiles();

		// 初始化所有的 HTTP 请求方法
		this.addRouteMethod("GET");
		this.addRouteMethod("POST");
		this.addRouteMethod("HEAD");
		this.addRouteMethod("PUT");
		this.addRouteMethod("DELETE");
		this.addRouteMethod("TRACE");
		this.addRouteMethod("CONNECT");
		this.addRouteMethod("OPTIONS");
		
		// Mime静态文件默认请求处理
		mimeFileRouter = new MimeFileRouter(webConfig.getContextPath());
		addRouteHandler("GET", MimeTools.getMimeTypeRegex(), mimeFileRouter);
	}

	/**
	 * 增加新的路由方法,例如:HTTP 方法 GET、POST 等等
	 * 
	 * @param method HTTP 请求方法
	 */
	protected void addRouteMethod(String method) {
		if (!methodRouters.containsKey(method)) {
		 Map<String,HttpRouter> routers = new TreeMap<String, HttpRouter>(new Comparator<String>() {
				@Override
				public int compare(String o1, String o2) {
					if(o1.length() >= o2.length() && !o1.equals(o2)){
						return -1;
					} else if(o1.length() < o2.length() &&!o1.equals(o2)){
						return 1;
					}else{
						return 0;
					}
				}
			});
			methodRouters.put(method, routers);
		}
	}

	/**
	 * 增加一个路由规则
	 * 
	 * @param method          Http 请求方法
	 * @param routeRegexPath  路径匹配正则
	 * @param router         请求处理句柄
	 */
	public void addRouteHandler(String method, String routeRegexPath, HttpRouter router) {
		if (methodRouters.keySet().contains(method)) {
			routeRegexPath = routeRegexPath.replaceAll("\\/{2,9}","/");
			methodRouters.get(method).put(routeRegexPath, router);
		}
	}

	/**
	 * Http 请求响应处理函数,入口函数
	 * 
	 * @param request    HTTP 请求
	 * @param response   HTTP 响应
	 */
	public void process(HttpRequest request, HttpResponse response){
		Chain<HttpFilterConfig> filterConfigs = webConfig.getFilterConfigs().clone();

		//Session预处理
		disposeSession(request,response);

		//正向过滤器处理,请求有可能被 Redirect 所以过滤器执行放在开始
		disposeFilter(filterConfigs,request,response);

		//调用处理路由函数
		disposeRoute(request,response);

		//反向过滤器处理
		disposeInvertedFilter(filterConfigs,request,response);

		//输出访问日志
		WebContext.writeAccessLog(request,response);
	}

	/**
	 * Http 路由处理函数
	 * @param request    Http请求对象
	 * @param response   Http 响应对象
     */
	public void disposeRoute(HttpRequest request, HttpResponse response){
		String requestPath 		= request.protocol().getPath();
		String requestMethod 	= request.protocol().getMethod();

		boolean isMatched = false;
		Map<String, HttpRouter> routers = methodRouters.get(requestMethod);

		//遍历路由对象
		for (Map.Entry<String,HttpRouter> routeEntry : routers.entrySet()) {
			String routePath = routeEntry.getKey();
			//寻找匹配的路由对象
			isMatched = matchPath(requestPath,routePath,webConfig.isMatchRouteIgnoreCase());
			if (isMatched) {

				//获取路由处理对象
				HttpRouter router = routeEntry.getValue();

				try {
					//获取路径变量
					Map<String, String> pathVariables = fetchPathVariables(requestPath,routePath);
					request.getParameters().putAll(pathVariables);

					//处理路由请求
                    router.process(request, response);

				} catch (Exception e) {
					exceptionMessage(request, response, e);
				}

				break;
			}
		}

		if(!isMatched) {
			//如果匹配失败,尝试用定义首页索引文件的名称
			if(!tryIndex(request,response)) {
				exceptionMessage(request, response, new RouterNotFound("Not avaliable router!"));
			}
		}
	}

	/**
	 * 尝试用定义首页索引文件的名称
	 * @param request   Http 请求对象
	 * @param response  Http 响应对象
     * @return 成功匹配到定义首页索引文件的名返回 true,否则返回 false
     */
	public boolean tryIndex(HttpRequest request,HttpResponse response){
		for (String indexFile : indexFiles) {
			String requestPath 	= request.protocol().getPath();
			String filePath = webConfig.getContextPath() + requestPath.replace("/",File.separator) + (requestPath.endsWith("/") ? "" : File.separator) + indexFile;
			if(TFile.fileExists(filePath)){
				try {
					String newRequestPath = requestPath + (requestPath.endsWith("/") ? "" : "/") + indexFile;
					request.protocol().setPath(newRequestPath);
					mimeFileRouter.process(request,response);
				} catch (Exception e) {
					exceptionMessage(request, response, e);
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * 将路径转换成正则表达式形式的路径
	 * @param routePath   匹配路径参数
	 * @return  转换后的正则匹配路径
     */
	public static String routePath2RegexPath(String routePath){
		String routeRegexPath = routePath.replaceAll("\\*",".*?");
		routeRegexPath = routeRegexPath.replaceAll(":[^/?]*", "[^/?]*");
		return routeRegexPath;
	}

	/**
	 * 路径匹配
	 * @param requestPath    请求路径
	 * @param routePath      正则匹配路径
	 * @param matchRouteIgnoreCase 路劲匹配是否忽略大消息
	 * @return  是否匹配成功
	 */
	public static boolean matchPath(String requestPath, String routePath,boolean matchRouteIgnoreCase){
		//转换成可以配置的正则,主要是处理:后的参数表达式
		//把/home/:name转换成/home/[^/?]+来匹配
		String routeRegexPath = routePath2RegexPath(routePath)+"[/]?$";
		//匹配路由不区分大小写
		if(matchRouteIgnoreCase){
			requestPath = requestPath.toLowerCase();
			routeRegexPath = routeRegexPath.toLowerCase();
		}
		if(TString.regexMatch(requestPath, routeRegexPath ) > 0 ){
			return true;
		}else if(TString.regexMatch(requestPath, routeRegexPath+"/$" ) > 0){
			return true;
		}
		return false ;
	}
	
	/**
	 * 获取路径变量,形如/:test/:name 的路径匹配的请求路径/test/var1后得到{name:var1}
	 * @param requestPath   请求路径
	 * @param routePath     正则匹配路径
	 * @return     路径抽取参数 Map
	 */
	public static Map<String, String> fetchPathVariables(String requestPath,String routePath) {
		Map<String, String> resultMap = new HashMap<String, String>();
		String[] requestPathPieces = requestPath.substring(1,requestPath.length()).split("/");
		String[] routePathPieces = routePath.substring(1, routePath.length()).split("/");

		if(requestPathPieces.length == routePathPieces.length){
			try {
				for (int i = 1; i <= routePathPieces.length; i++) {
					int routePathPiecesLength = routePathPieces.length;
					int pathPiecesLength = requestPathPieces.length;
					String routePathPiece = routePathPieces[routePathPiecesLength - i];
					if (routePathPiece.startsWith(":")) {
						String name = TString.removePrefix(routePathPiece);
						String value = URLDecoder.decode(requestPathPieces[pathPiecesLength - i], "UTF-8");
						resultMap.put(name, value);
					}
				}
			} catch (UnsupportedEncodingException e) {
				Logger.error("RoutePath URLDecoder.decode failed by charset: UTF-8", e);
			}
		}
		return resultMap;
	}
	
	/**
	 * 处理 Session
	 * @param request   HTTP 请求
	 * @param response  HTTP 响应
	 */
	public void disposeSession(HttpRequest request, HttpResponse response){
		
		//获取请求的 Cookie中的session标识
		Cookie sessionCookie = request.getCookie(WebContext.getSessionName());

        //如果 session 不存在,创建新的 session
        if (!sessionManager.containsSession(sessionCookie)) {
            // 构建 session
            HttpSession httpSession = sessionManager.newHttpSession(request, response);

            // 请求增加 Session
            request.setSession(httpSession);
        } else {
            // 通过 Cookie 中的 session 标识获取 Session
            HttpSession httpSession = sessionManager.getSession(sessionCookie.getValue());

            // 请求增加 Session
            request.setSession(httpSession);
        }
	}

	/**
	 * //正向处理过滤器
	 * @param filterConfigs   HTTP过滤器配置对象
	 * @param request		  请求对象
	 * @param response		  响应对象
     */
	public void disposeFilter(Chain<HttpFilterConfig> filterConfigs, HttpRequest request, HttpResponse response) {
		filterConfigs.rewind();
		Object filterResult = null;
		while(filterConfigs.hasNext()){
			HttpFilterConfig filterConfig = filterConfigs.next();
			HttpFilter httpFilter = filterConfig.getHttpFilterInstance();
			if(httpFilter!=null) {
				filterResult = httpFilter.onRequest(filterConfig, request, response, filterResult);
			}
		}
	}

	/**
	 * 反向处理过滤器
	 * @param filterConfigs   HTTP过滤器配置对象
	 * @param request		  请求对象
	 * @param response		  响应对象
     */
	public void disposeInvertedFilter(Chain<HttpFilterConfig> filterConfigs, HttpRequest request, HttpResponse response) {
		filterConfigs.rewind();
		Object filterResult = null;
		while(filterConfigs.hasPrevious()){
			HttpFilterConfig filterConfig = filterConfigs.previous();
			HttpFilter httpFilter = filterConfig.getHttpFilterInstance();
			if(httpFilter!=null) {
				filterResult = httpFilter.onResponse(filterConfig, request, response, filterResult);
			}

		}
	}
	
	/**
	 * 异常消息处理
	 * 
	 * @param request  请求对象
	 * @param response 响应对象
	 * @param e  异常对象
	 */
	public void exceptionMessage(HttpRequest request, HttpResponse response, Exception e) {
		
		//获取配置文件异常定义
		Map<String, Object> errorDefine = WebContext.getErrorDefine();

		//信息准备
		String requestMethod = request.protocol().getMethod();
		String requestPath = request.protocol().getPath();
		String className = e.getClass().getName();
		String errorMessage = e.toString();
		String stackInfo = TEnv.getStackMessage();
		//转换成能在 HTML 中展示的超文本字符串
		stackInfo = TString.indent(stackInfo,1).replace("\n", "<br>");
		response.header().put("Content-Type", "text/html");

		//初始 error 定义,如果下面匹配到了定义的错误则定义的会被覆盖
		Map<String, Object> error = new HashMap<String, Object>();

		//输出异常
		if( !(e instanceof ResourceNotFound || e instanceof RouterNotFound) ){
			error.put("StatusCode", 500);
			Logger.error(e);
		}else{
			error.put("StatusCode", 404);
		}
		error.put("Page", "Error.html");
		error.put("Description", stackInfo);
		
		//匹配 error 定义,如果有可用消息则会覆盖上面定义的初始内容
		if (errorDefine.containsKey(className)) {
			error.putAll(TObject.cast(errorDefine.get(className)));
			response.protocol().setStatus(TObject.cast(error.get("StatusCode")));
		} else if (errorDefine.get("Other") != null) {
			error.putAll(TObject.cast(errorDefine.get("Other")));
			response.protocol().setStatus(TObject.cast(error.get("StatusCode")));
		}
		
		//消息拼装
		String errorPageContent = WebContext.getDefaultErrorPage();
		if(TFile.fileExists(TEnv.getSystemPath("/conf/error-page/" + error.get("Page")))) {
			try {
				errorPageContent = new String(TFile.loadFileFromContextPath("/conf/error-page/" + error.get("Page")),"UTF-8");
			} catch (UnsupportedEncodingException e1) {
				Logger.error("This charset is unsupported.",e);
			}
		}
		if(errorPageContent!=null){
			errorPageContent = TString.tokenReplace(errorPageContent, "StatusCode", error.get("StatusCode").toString());
			errorPageContent = TString.tokenReplace(errorPageContent, "RequestMethod", requestMethod);
			errorPageContent = TString.tokenReplace(errorPageContent, "RequestPath", requestPath);
			errorPageContent = TString.tokenReplace(errorPageContent, "ErrorMessage", errorMessage);
			errorPageContent = TString.tokenReplace(errorPageContent, "Description", error.get("Description").toString());
			errorPageContent = TString.tokenReplace(errorPageContent, "Version", WebContext.getVERSION());
			errorPageContent = TString.tokenReplace(errorPageContent, "DateTime", TDateTime.now());
			response.clear();
			response.write(errorPageContent);
		}
	}
}
