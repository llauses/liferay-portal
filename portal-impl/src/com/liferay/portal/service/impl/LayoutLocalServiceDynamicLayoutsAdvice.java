/**
 * Copyright (c) 2000-2011 Liferay, Inc. All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 */

package com.liferay.portal.service.impl;

import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.exception.SystemException;
import com.liferay.portal.kernel.lar.PortletDataHandlerKeys;
import com.liferay.portal.kernel.lar.UserIdStrategy;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.util.DateFormatFactoryUtil;
import com.liferay.portal.kernel.util.FileUtil;
import com.liferay.portal.kernel.util.ListUtil;
import com.liferay.portal.kernel.util.StringBundler;
import com.liferay.portal.kernel.util.SystemProperties;
import com.liferay.portal.kernel.util.UnicodeProperties;
import com.liferay.portal.kernel.util.Validator;
import com.liferay.portal.model.Group;
import com.liferay.portal.model.Layout;
import com.liferay.portal.model.LayoutConstants;
import com.liferay.portal.model.LayoutSet;
import com.liferay.portal.model.LayoutSetPrototype;
import com.liferay.portal.model.ResourceConstants;
import com.liferay.portal.model.ResourcePermission;
import com.liferay.portal.model.UserGroup;
import com.liferay.portal.model.impl.VirtualLayout;
import com.liferay.portal.security.permission.PermissionChecker;
import com.liferay.portal.security.permission.PermissionThreadLocal;
import com.liferay.portal.service.GroupLocalServiceUtil;
import com.liferay.portal.service.LayoutLocalServiceUtil;
import com.liferay.portal.service.LayoutSetLocalServiceUtil;
import com.liferay.portal.service.LayoutSetPrototypeLocalServiceUtil;
import com.liferay.portal.service.ResourcePermissionLocalServiceUtil;
import com.liferay.portal.service.UserGroupLocalServiceUtil;
import com.liferay.portal.util.PropsValues;

import java.io.File;

import java.lang.reflect.Method;

import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.text.DateFormat;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;

import org.springframework.core.annotation.Order;

/**
 * @author Raymond Augé
 */
@Order(2)
public class LayoutLocalServiceDynamicLayoutsAdvice
	implements MethodInterceptor {

	public Object invoke(MethodInvocation methodInvocation) throws Throwable {
		Method method = methodInvocation.getMethod();

		String methodName = method.getName();

		Object[] arguments = methodInvocation.getArguments();

		Class<?>[] parameterTypes = method.getParameterTypes();

		if (methodName.equals("getLayouts") &&
			 Arrays.equals(parameterTypes, _TYPES_L_B_L) ||
			 Arrays.equals(parameterTypes, _TYPES_L_B_L_B_I_I)) {

			long groupId = (Long)arguments[0];
			boolean privateLayout = (Boolean)arguments[1];
			long parentLayoutId = (Long)arguments[2];

			PermissionChecker permissionChecker =
				PermissionThreadLocal.getPermissionChecker();

			try {
				Group group = GroupLocalServiceUtil.getGroup(groupId);

				LayoutSet layoutSet = LayoutSetLocalServiceUtil.getLayoutSet(
					groupId, privateLayout);

				mergeLayoutSetProtypeLayouts(
					permissionChecker, group, layoutSet);

				if (!PropsValues.USER_GROUPS_COPY_LAYOUTS_TO_USER_PERSONAL_SITE &&
					group.isUser() &&
					(parentLayoutId == LayoutConstants.DEFAULT_PARENT_LAYOUT_ID)) {

					Object returnValue = methodInvocation.proceed();

					return addUserGroupLayouts(
						permissionChecker, group, layoutSet,
						(List<Layout>)returnValue);
				}
			}
			catch (Exception e) {
				_log.error(e, e);

				throw e;
			}
		}

		return methodInvocation.proceed();
	}

	protected List<Layout> addUserGroupLayouts(
			PermissionChecker permissionChecker, Group group,
			LayoutSet layoutSet, List<Layout> layouts)
		throws Exception {

		layouts = ListUtil.copy(layouts);

		List<UserGroup> userUserGroups =
			UserGroupLocalServiceUtil.getUserUserGroups(group.getClassPK());

		for (UserGroup userGroup : userUserGroups) {
			Group userGroupGroup = userGroup.getGroup();

			List<Layout> userGroupLayouts = LayoutLocalServiceUtil.getLayouts(
				userGroupGroup.getGroupId(), layoutSet.isPrivateLayout());

			for (Layout userGroupLayout : userGroupLayouts) {
				layouts.add(new VirtualLayout(userGroupLayout, group));
			}
		}

		return layouts;
	}

	protected void mergeLayoutSetProtypeLayouts(
			PermissionChecker permissionChecker, Group group,
			LayoutSet layoutSet)
		throws Exception {

		if ((permissionChecker == null) || !permissionChecker.isSignedIn() ||
			!layoutSet.getLayoutSetPrototypeLinkEnabled() ||
			Validator.isNull(layoutSet.getLayoutSetPrototypeUuid()) ||
			group.isLayoutPrototype() || group.isLayoutSetPrototype()) {

			return;
		}

		LayoutSetPrototype layoutSetPrototype =
			LayoutSetPrototypeLocalServiceUtil.getLayoutSetPrototypeByUuid(
				layoutSet.getLayoutSetPrototypeUuid());

		UnicodeProperties settingsProperties =
			layoutSet.getSettingsProperties();

		DateFormat dateFormat = DateFormatFactoryUtil.getSimpleDateFormat(
			PropsValues.INDEX_DATE_FORMAT_PATTERN);

		String lastMergeDateString = settingsProperties.getProperty(
			_LAST_MERGE_DATE, _NULL_DATE);

		Date lastMergeDate = dateFormat.parse(lastMergeDateString);

		if (!layoutSetPrototype.getModifiedDate().after(lastMergeDate)) {
			return;
		}

		Map<String, String[]> parameterMap = null;

		if (lastMergeDateString.equals(_NULL_DATE)) {
			parameterMap = getLayoutTemplatesParameters(true);
		}
		else {
			parameterMap = getLayoutTemplatesParameters(false);
		}

		importLayoutSetPrototype(
			permissionChecker, layoutSetPrototype, layoutSet.getGroupId(),
			layoutSet.isPrivateLayout(), parameterMap);

		settingsProperties.setProperty(
			_LAST_MERGE_DATE, dateFormat.format(
				layoutSetPrototype.getModifiedDate()));

		LayoutSetLocalServiceUtil.updateLayoutSet(layoutSet, false);
	}

	protected Map<String, String[]> getLayoutTemplatesParameters(
		boolean firstTime) {

		Map<String, String[]> parameterMap =
			new LinkedHashMap<String, String[]>();

		if (firstTime) {
			parameterMap.put(
				PortletDataHandlerKeys.DATA_STRATEGY,
				new String[] {PortletDataHandlerKeys.DATA_STRATEGY_MIRROR});
			parameterMap.put(
				PortletDataHandlerKeys.PORTLET_DATA,
				new String[] {Boolean.TRUE.toString()});
			parameterMap.put(
				PortletDataHandlerKeys.PORTLET_DATA_ALL,
				new String[] {Boolean.TRUE.toString()});
		}
		else {
			parameterMap.put(
				PortletDataHandlerKeys.PORTLET_DATA,
				new String[] {Boolean.FALSE.toString()});
			parameterMap.put(
				PortletDataHandlerKeys.PORTLET_DATA_ALL,
				new String[] {Boolean.FALSE.toString()});
		}

		parameterMap.put(
			PortletDataHandlerKeys.LAYOUT_SET_PROTOTYPE_INHERITED,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.CATEGORIES,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.DELETE_MISSING_LAYOUTS,
			new String[] {Boolean.FALSE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.DELETE_PORTLET_DATA,
			new String[] {Boolean.FALSE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.IGNORE_LAST_PUBLISH_DATE,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.PERMISSIONS,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.PORTLET_ARCHIVED_SETUPS,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.PORTLET_SETUP,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.THEME,
			new String[] {Boolean.FALSE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.THEME_REFERENCE,
			new String[] {Boolean.TRUE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.UPDATE_LAST_PUBLISH_DATE,
			new String[] {Boolean.FALSE.toString()});
		parameterMap.put(
			PortletDataHandlerKeys.USER_ID_STRATEGY,
			new String[] {UserIdStrategy.CURRENT_USER_ID});

		return parameterMap;
	}

	protected long getOwnerUserId(
			PermissionChecker permissionChecker,
			LayoutSetPrototype layoutSetPrototype)
		throws PortalException, SystemException {

		ResourcePermission resourcePermission =
			ResourcePermissionLocalServiceUtil.getResourcePermission(
				layoutSetPrototype.getCompanyId(),
				LayoutSetPrototype.class.getName(),
				ResourceConstants.SCOPE_INDIVIDUAL,
				String.valueOf(
					layoutSetPrototype.getLayoutSetPrototypeId()),
				permissionChecker.getOwnerRoleId());

		return resourcePermission.getOwnerId();
	}

	protected void importLayoutSetPrototype(
			PermissionChecker permissionChecker,
			LayoutSetPrototype layoutSetPrototype, long groupId,
			boolean privateLayout, Map<String, String[]> parameterMap)
		throws PortalException, SystemException {

		StringBundler sb = new StringBundler(3);

		sb.append(_TEMP_DIR);
		sb.append(layoutSetPrototype.getUuid());
		sb.append(".lar");

		long modifiedTime = layoutSetPrototype.getModifiedDate().getTime();

		File cacheFile = new File(sb.toString());
		File file = null;
		boolean isNewFile = false;

		if (cacheFile.exists() &&
			(cacheFile.lastModified() >= modifiedTime)) {

			if (_log.isDebugEnabled()) {
				_log.debug(
					"Using cached layoutSetPrototype LAR file " +
						cacheFile.getAbsolutePath());
			}

			file = cacheFile;
		}

		Group layoutSetPrototypeGroup = layoutSetPrototype.getGroup();

		long userId = getOwnerUserId(permissionChecker, layoutSetPrototype);

		if (file == null) {
			file = LayoutLocalServiceUtil.exportLayoutsAsFile(
				layoutSetPrototypeGroup.getGroupId(), true, null,
				parameterMap, null, null);

			isNewFile = true;
		}

		LayoutLocalServiceUtil.importLayouts(
			userId, groupId, privateLayout, parameterMap, file);

		if (isNewFile) {
			try {
				FileUtil.copyFile(file, cacheFile);

				if (_log.isDebugEnabled()) {
					_log.debug(
						"Copied " + file.getAbsolutePath() + " to " +
							cacheFile.getAbsolutePath());
				}
			}
			catch (Exception e) {
				_log.error(
					"File " + file.getAbsolutePath() +
						" could not be copied to " +
							cacheFile.getAbsolutePath(), e);
			}
		}
	}

	private static Log _log = LogFactoryUtil.getLog(
		LayoutLocalServiceDynamicLayoutsAdvice.class);

	private static final String _LAST_MERGE_DATE = "lastMergeDate";

	private static final String _NULL_DATE = "00000000000000";

	private static final String _TEMP_DIR =
		SystemProperties.get(SystemProperties.TMP_DIR) +
			"/liferay/layout_set_prototype/";

	private static final Class<?>[] _TYPES_L_B_L = new Class<?>[] {
		Long.TYPE, Boolean.TYPE, Long.TYPE
	};
	private static final Class<?>[] _TYPES_L_B_L_B_I_I = new Class<?>[] {
		Long.TYPE, Boolean.TYPE, Long.TYPE, Boolean.TYPE, Integer.TYPE,
		Integer.TYPE
	};

}