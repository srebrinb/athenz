/*
 * Copyright 2019 Oath Holdings Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yahoo.athenz.common.server.notification.impl;

import com.amazonaws.util.IOUtils;
import com.yahoo.athenz.common.server.notification.EmailProvider;
import com.yahoo.athenz.common.server.notification.Notification;
import com.yahoo.athenz.common.server.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.yahoo.athenz.common.server.notification.NotificationServiceConstants.*;

/*
 * Email based notification service.
 */
public class EmailNotificationService implements NotificationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailNotificationService.class);

    private static final String AT = "@";
    private static final String USER_DOMAIN_DEFAULT = "user";
    private static final String PROP_USER_DOMAIN = "athenz.user_domain";
    private static final String PROP_NOTIFICATION_EMAIL_DOMAIN_FROM = "athenz.notification_email_domain_from";
    private static final String PROP_NOTIFICATION_EMAIL_DOMAIN_TO = "athenz.notification_email_domain_to";
    private static final String PROP_NOTIFICATION_WORKFLOW_URL = "athenz.notification_workflow_url";
    private static final String PROP_NOTIFICATION_ATHENZ_UI_URL = "athenz.notification_athenz_ui_url";
    private static final String PROP_NOTIFICATION_EMAIL_FROM = "athenz.notification_email_from";

    private static final String MEMBERSHIP_APPROVAL_SUBJECT = "athenz.notification.email.membership.approval.subject";
    private static final String MEMBERSHIP_APPROVAL_REMINDER_SUBJECT = "athenz.notification.email.membership.reminder.subject";

    private static final String DOMAIN_MEMBER_EXPIRY_SUBJECT = "athenz.notification.email.domain.member.expiry.subject";
    private static final String DOMAIN_MEMBER_EXPIRY_BODY_ENTRY = "athenz.notification.email.domain.member.expiry.body.entry";

    private static final String PRINCIPAL_EXPIRY_SUBJECT = "athenz.notification.email.principal.expiry.subject";
    private static final String PRINCIPAL_EXPIRY_BODY_ENTRY = "athenz.notification.email.principal.expiry.body.entry";

    private static final int SES_RECIPIENTS_LIMIT_PER_MESSAGE = 50;

    // can be moved to constructor which can take Locale as input parameter and return appropriate resource bundle
    private static final ResourceBundle RB = ResourceBundle.getBundle("messages/ServerCommon");

    private static final String EMAIL_TEMPLATE_NOTIFICATION_APPROVAL = "messages/membership-approval.html";
    private static final String EMAIL_TEMPLATE_NOTIFICATION_APPROVAL_REMINDER = "messages/membership-approval-reminder.html";
    private static final String EMAIL_TEMPLATE_DOMAIN_MEMBER_EXPIRY = "messages/domain-member-expiry.html";
    private static final String EMAIL_TEMPLATE_PRINCIPAL_EXPIRY = "messages/principal-expiry.html";
    private static final String EMAIL_TEMPLATE_ATHENZ_LOGO = "emails/athenz-logo-white.png";
    private static final String EMAIL_TEMPLATE_CSS = "emails/base.css";


    private static final String HTML_STYLE_TAG_START = "<style>";
    private static final String HTML_STYLE_TAG_END = "</style>";
    private static final String HTML_TBODY_TAG_START = "<tbody>";
    private static final String HTML_TBODY_TAG_END = "</tbody>";

    private final EmailProvider emailProvider;

    private String userDomainPrefix;
    private String emailDomainFrom;
    private String emailDomainTo;
    private String workflowUrl;
    private String athenzUIUrl;
    private String from;

    private byte[] logoImage;
    private String emailBaseCSS;
    private String emailMembershipApprovalBody;
    private String emailMembershipApprovalReminderBody;
    private String emailDomainMemberExpiryBody;
    private String emailPrincipalExpiryBody;

    EmailNotificationService(EmailProvider emailProvider) {
        this.emailProvider = emailProvider;
        String userDomain = System.getProperty(PROP_USER_DOMAIN, USER_DOMAIN_DEFAULT);
        userDomainPrefix = userDomain + "\\.";
        emailDomainFrom = System.getProperty(PROP_NOTIFICATION_EMAIL_DOMAIN_FROM);
        emailDomainTo = System.getProperty(PROP_NOTIFICATION_EMAIL_DOMAIN_TO);
        workflowUrl = System.getProperty(PROP_NOTIFICATION_WORKFLOW_URL);
        athenzUIUrl = System.getProperty(PROP_NOTIFICATION_ATHENZ_UI_URL);
        from = System.getProperty(PROP_NOTIFICATION_EMAIL_FROM);

        logoImage = readBinaryFromFile(EMAIL_TEMPLATE_ATHENZ_LOGO);
        emailBaseCSS = readContentFromFile(EMAIL_TEMPLATE_CSS);
        emailMembershipApprovalBody = readContentFromFile(EMAIL_TEMPLATE_NOTIFICATION_APPROVAL);
        emailMembershipApprovalReminderBody = readContentFromFile(EMAIL_TEMPLATE_NOTIFICATION_APPROVAL_REMINDER);
        emailDomainMemberExpiryBody = readContentFromFile(EMAIL_TEMPLATE_DOMAIN_MEMBER_EXPIRY);
        emailPrincipalExpiryBody =  readContentFromFile(EMAIL_TEMPLATE_PRINCIPAL_EXPIRY);
    }

    byte[] readBinaryFromFile(String fileName) {

        byte[] fileByteArray = null;
        URL resource = getClass().getClassLoader().getResource(fileName);
        if (resource != null) {
            try (InputStream fileStream = resource.openStream()) {
                //convert to byte array
                fileByteArray = IOUtils.toByteArray(fileStream);

            } catch (IOException ex) {
                LOGGER.error("Could not read file: {}. Error message: {}", fileName, ex.getMessage());
            }
        }
        return fileByteArray;
    }

    @Override
    public boolean notify(Notification notification) {
        if (notification == null) {
            return false;
        }
        final String subject = getSubject(notification.getType());
        final String body = getBody(notification.getType(), notification.getDetails());
        Set<String> recipients = getFullyQualifiedEmailAddresses(notification.getRecipients());
        return sendEmail(recipients, subject, body);
    }

    String getBody(String type, Map<String, String> details) {

        String body = "";
        switch (type) {
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL:
                body = getMembershipApprovalBody(details);
                break;
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL_REMINDER:
                body = getMembershipApprovalReminderBody();
                break;
            case NOTIFICATION_TYPE_DOMAIN_MEMBER_EXPIRY_REMINDER:
                body = getDomainMemberExpiryBody(details);
                break;
            case NOTIFICATION_TYPE_PRINCIPAL_EXPIRY_REMINDER:
                body = getPrincipalExpiryBody(details);
                break;
        }
        body = body.replace(HTML_STYLE_TAG_START + HTML_STYLE_TAG_END, HTML_STYLE_TAG_START + emailBaseCSS + HTML_STYLE_TAG_END);
        return body;
    }

    String getSubject(String type) {
        String subject = "";
        switch (type) {
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL:
                subject = RB.getString(MEMBERSHIP_APPROVAL_SUBJECT);
                break;
            case NOTIFICATION_TYPE_MEMBERSHIP_APPROVAL_REMINDER:
                subject = RB.getString(MEMBERSHIP_APPROVAL_REMINDER_SUBJECT);
                break;
            case NOTIFICATION_TYPE_DOMAIN_MEMBER_EXPIRY_REMINDER:
                subject = RB.getString(DOMAIN_MEMBER_EXPIRY_SUBJECT);
                break;
            case NOTIFICATION_TYPE_PRINCIPAL_EXPIRY_REMINDER:
                subject = RB.getString(PRINCIPAL_EXPIRY_SUBJECT);
                break;
        }
        return subject;
    }

    Set<String> getFullyQualifiedEmailAddresses(Set<String> recipients) {
        return recipients.stream()
                .map(s -> s.replaceAll(userDomainPrefix, ""))
                .map(r -> r + AT + emailDomainTo)
                .collect(Collectors.toSet());
    }

    String getMembershipApprovalReminderBody() {
        return MessageFormat.format(emailMembershipApprovalReminderBody, workflowUrl, athenzUIUrl);
    }

    String getMembershipApprovalBody(Map<String, String> metaDetails) {
        return MessageFormat.format(emailMembershipApprovalBody, metaDetails.get(NOTIFICATION_DETAILS_DOMAIN),
                metaDetails.get(NOTIFICATION_DETAILS_ROLE), metaDetails.get(NOTIFICATION_DETAILS_MEMBER),
                metaDetails.get(NOTIFICATION_DETAILS_REASON), metaDetails.get(NOTIFICATION_DETAILS_REQUESTER),
                workflowUrl, athenzUIUrl);
    }

    String getDomainMemberExpiryBody(Map<String, String> metaDetails) {

        // first get the template and replace placeholders
        StringBuilder body = new StringBuilder(256);
        body.append(MessageFormat.format(emailDomainMemberExpiryBody, metaDetails.get(NOTIFICATION_DETAILS_DOMAIN), athenzUIUrl));

        // then get table rows and replace placeholders
        StringBuilder bodyEntry = new StringBuilder(256);
        final String roleNames = metaDetails.get(NOTIFICATION_DETAILS_EXPIRY_MEMBERS);
        processExpiryEntry(bodyEntry, roleNames, RB.getString(DOMAIN_MEMBER_EXPIRY_BODY_ENTRY));

        // add table rows to the template
        return body.toString().replace(HTML_TBODY_TAG_START + HTML_TBODY_TAG_END, HTML_TBODY_TAG_START + bodyEntry + HTML_TBODY_TAG_END);
    }

    String getPrincipalExpiryBody(Map<String, String> metaDetails) {

        // first get the template and replace placeholders
        StringBuilder body = new StringBuilder(256);
        body.append(MessageFormat.format(emailPrincipalExpiryBody, metaDetails.get(NOTIFICATION_DETAILS_MEMBER), athenzUIUrl));

        // then get table rows and replace placeholders
        StringBuilder bodyEntry = new StringBuilder(256);
        final String roleNames = metaDetails.get(NOTIFICATION_DETAILS_EXPIRY_ROLES);
        processExpiryEntry(bodyEntry, roleNames, RB.getString(PRINCIPAL_EXPIRY_BODY_ENTRY));

        // add table rows to the template
        return body.toString().replace(HTML_TBODY_TAG_START + HTML_TBODY_TAG_END, HTML_TBODY_TAG_START + bodyEntry + HTML_TBODY_TAG_END);
    }

    void processExpiryEntry(StringBuilder body, final String entryNames, final String entryFormat) {
        // if we have no entry names then there is nothing to process
        if (entryNames == null) {
            return;
        }
        String[] entries = entryNames.split("\\|");
        for (String entry : entries) {
            String[] comps = entry.split(";");
            if (comps.length != 3) {
                continue;
            }
            body.append(MessageFormat.format(entryFormat, comps[0], comps[1], comps[2]));
            body.append('\n');
        }
    }

    boolean sendEmail(Set<String> recipients, String subject, String body) {
        final AtomicInteger counter = new AtomicInteger();
        boolean status = true;
        // SES imposes a limit of 50 recipients. So we convert the recipients into batches
        if (recipients.size() > SES_RECIPIENTS_LIMIT_PER_MESSAGE) {
            final Collection<List<String>> recipientsBatch = recipients.stream()
                    .collect(Collectors.groupingBy(it -> counter.getAndIncrement() / SES_RECIPIENTS_LIMIT_PER_MESSAGE))
                    .values();
            for (List<String> recipientsSegment : recipientsBatch) {
                status = sendEmailMIME(subject, body, status, recipientsSegment);
            }
        } else {
            status = sendEmailMIME(subject, body, status, new ArrayList<>(recipients));
        }

        return status;
    }

    String readContentFromFile(String fileName) {
        StringBuilder contents = new StringBuilder();
        URL resource = getClass().getClassLoader().getResource(fileName);
        if (resource != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resource.openStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    contents.append(line);
                    contents.append(System.getProperty("line.separator"));
                }
            } catch (IOException ex) {
                LOGGER.error("Could not read file: {}. Error message: {}", fileName, ex.getMessage());
            }
        }
        return contents.toString();
    }

    private boolean sendEmailMIME(String subject, String body, boolean status, Collection<String> recipients) {
        return emailProvider.sendEmail(subject, body, status, recipients, from + AT + emailDomainFrom, logoImage);
    }
}
