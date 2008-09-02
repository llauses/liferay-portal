<%
/**
 * Copyright (c) 2000-2008 Liferay, Inc. All rights reserved.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
%>

<%@ include file="/html/taglib/ui/tags_navigation/init.jsp"%>

<%
Boolean showCompanyCategories = (Boolean)request.getAttribute("liferay-ui:tags-navigation:showCompanyCategories");

long entryId = ParamUtil.getLong(renderRequest, "entryId");

List<TagsVocabulary> vocabularies = null;

if (showCompanyCategories.booleanValue()) {
	vocabularies = TagsVocabularyServiceUtil.getCompanyVocabularies(company.getCompanyId(), false);
}
else {
	vocabularies = TagsVocabularyServiceUtil.getGroupVocabularies(portletGroupId.longValue(), false);
}

PortletURL portletURL = renderResponse.createRenderURL();
%>

<div>

	<%
	StringBuilder sb = new StringBuilder();

	sb.append("<ul class='categories-navigation-treeview'>");

	for (TagsVocabulary vocabulary : vocabularies) {
		String vocabularyName = vocabulary.getName();

		sb.append("<li class='tags-vocabulary-name'>");
		sb.append("<span>");
		sb.append(vocabularyName);
		sb.append("</span>");

		List<TagsEntry> rootEntries = TagsEntryServiceUtil.getGroupVocabularyRootEntries(vocabulary.getGroupId(), vocabularyName);
		_buildNavigation(rootEntries, vocabularyName, entryId, portletURL, sb);
	}

	sb.append("</li>");
	sb.append("</ul>");
	out.print(sb.toString());
	%>

</div>

<script type="text/javascript" charset="utf-8">
	jQuery(document).ready(
		function() {
			var treeview = jQuery('.categories-navigation-treeview');

			treeview.treeview(
				{
					animated: 'fast'
				}
			);

			jQuery.ui.disableSelection(treeview);
		}
	);
</script>

<%!
private void _buildNavigation(List<TagsEntry> entries, String vocabularyName, long entryId, PortletURL portletURL, StringBuilder sb) throws Exception {
	for (TagsEntry entry : entries) {
		String entryName = entry.getName();
		List<TagsEntry> childrenEntries = TagsEntryServiceUtil.getGroupVocabularyEntries(entry.getGroupId(), entryName, vocabularyName);

		sb.append("<ul>");
		sb.append("<li>");
		sb.append("<span>");

		if (entry.getEntryId() == entryId) {
			sb.append("<b>");
			sb.append(entryName);
			sb.append("</b>");
		}
		else {
			portletURL.setParameter("entryId", String.valueOf(entry.getEntryId()));

			sb.append("<a href='");
			sb.append(portletURL.toString());
			sb.append("'>");
			sb.append(entryName);
			sb.append("</a>");
		}

		sb.append("</span>");

		_buildNavigation(childrenEntries, vocabularyName, entryId, portletURL, sb);

		sb.append("</li>");
		sb.append("</ul>");
	}
}
%>