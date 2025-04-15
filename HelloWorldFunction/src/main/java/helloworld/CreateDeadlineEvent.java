package helloworld;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent;
import com.amazonaws.services.lambda.runtime.events.DynamodbEvent.DynamodbStreamRecord;
import com.amazonaws.services.lambda.runtime.events.models.dynamodb.AttributeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.scheduler.SchedulerClient;
import software.amazon.awssdk.services.scheduler.model.CreateScheduleRequest;
import software.amazon.awssdk.services.scheduler.model.FlexibleTimeWindow;
import software.amazon.awssdk.services.scheduler.model.ScheduleState;
import software.amazon.awssdk.services.scheduler.model.Target;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

public class CreateDeadlineEvent implements RequestHandler<DynamodbEvent, Void> {

    private static final Logger logger = LoggerFactory.getLogger(CreateDeadlineEvent.class);
    // ARN of the target Lambda for reminders (replace with actual ARN)
    private static final String TARGET_LAMBDA_ARN = "arn:aws:lambda:us-east-1:123456789012:function:ReminderProcessorLambda";
    // Role ARN with permissions to invoke the target Lambda
    private static final String SCHEDULER_ROLE_ARN = "arn:aws:iam::123456789012:role/EventBridgeSchedulerRole";
    // Reminder offset (1 hour before deadline)
    private static final long REMINDER_OFFSET_MINUTES = 60;

    private final SchedulerClient schedulerClient;

    public CreateDeadlineEvent() {
        // Initialize AWS SDK v2 SchedulerClient
        this.schedulerClient = SchedulerClient.create();
    }

    @Override
    public Void handleRequest(DynamodbEvent event, Context context) {
        // Iterate through each record in the DynamoDB stream event
        for (DynamodbStreamRecord record : event.getRecords()) {
            // Check for create (INSERT) or update (MODIFY) operations
            String eventName = record.getEventName();
            if ("INSERT".equals(eventName) || "MODIFY".equals(eventName)) {
                try {
                    // Extract taskId from the new image
                    Map<String, AttributeValue> newImage = record.getDynamodb().getNewImage();
                    String taskId = newImage.get("taskId").getS();
                    logger.info("Processing {} event for taskId: {}", eventName, taskId);

                    // Extract deadline
                    if (!newImage.containsKey("deadline") || newImage.get("deadline").getS() == null) {
                        logger.warn("No deadline found for taskId: {}", taskId);
                        continue;
                    }
                    String deadlineStr = newImage.get("deadline").getS();

                    // Parse deadline and calculate reminder time
                    OffsetDateTime deadline;
                    try {
                        deadline = OffsetDateTime.parse(deadlineStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    } catch (DateTimeParseException e) {
                        logger.error("Invalid deadline format for taskId: {}: {}", taskId, deadlineStr);
                        continue;
                    }

                    OffsetDateTime reminderTime = deadline.minusMinutes(REMINDER_OFFSET_MINUTES);
                    OffsetDateTime now = OffsetDateTime.now();

                    // Skip if reminder time is in the past
                    if (reminderTime.isBefore(now)) {
                        logger.warn("Reminder time {} is in the past for taskId: {}", reminderTime, taskId);
                        continue;
                    }

                    // Create EventBridge schedule
                    createSchedule(taskId, reminderTime, newImage);

                    // Log additional details for debugging
                    logger.debug("Record details: {}", newImage);

                } catch (Exception e) {
                    logger.error("Error processing record for event {}: {}", eventName, e.getMessage());
                }
            } else {
                // Skip other events like REMOVE
                logger.debug("Skipping event: {}", eventName);
            }
        }
        return null;
    }

    private void createSchedule(String taskId, OffsetDateTime reminderTime, Map<String, AttributeValue> taskItem) {
        try {
            // Format reminder time for EventBridge
            String scheduleExpression = "at(" + reminderTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace("Z", "") + ")";

            // Prepare event payload for target Lambda
            Map<String, String> inputPayload = new HashMap<>();
            inputPayload.put("taskId", taskId);
            taskItem.forEach((key, value) -> {
                if (value.getS() != null) {
                    inputPayload.put(key, value.getS());
                }
            });

            // Create schedule request
            CreateScheduleRequest request = CreateScheduleRequest.builder()
                    .name("TaskReminder_" + taskId)
                    .scheduleExpression(scheduleExpression)
                    .state(ScheduleState.ENABLED)
                    .flexibleTimeWindow(FlexibleTimeWindow.builder().mode("OFF").build())
                    .target(Target.builder()
                            .arn(TARGET_LAMBDA_ARN)
                            .roleArn(SCHEDULER_ROLE_ARN)
                            .input(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(inputPayload))
                            .build())
                    .build();

            // Create the schedule
            schedulerClient.createSchedule(request);
            logger.info("Created EventBridge schedule for taskId: {} at {}", taskId, reminderTime);

        } catch (Exception e) {
            logger.error("Failed to create schedule for taskId: {}: {}", taskId, e.getMessage());
        }
    }

}