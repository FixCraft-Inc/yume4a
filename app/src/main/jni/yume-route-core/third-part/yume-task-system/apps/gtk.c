/*
 ============================================================================
 Name        : gtk.c
 Author      : Heiher <r@hev.cc>
 Copyright   : Copyright (c) 2017 everyone.
 Description :
 ============================================================================
 */

#include <stdio.h>

#include <yume-task.h>
#include <yume-task-system.h>
#include <yume-task-io-poll.h>

#include <glib.h>
#include <gtk/gtk.h>

static gboolean
window_delete_event_handler (GtkWidget *widget, GdkEvent *event,
                             gpointer user_data)
{
    gtk_main_quit ();

    return FALSE;
}

static void
task_entry1 (void *data)
{
    GtkWidget *window;

    gtk_init (NULL, NULL);

    /* Use yume_task_poll to poll fds in GLib event loop */
    g_main_context_set_poll_func (g_main_context_default (),
                                  (GPollFunc)yume_task_io_poll);

    window = gtk_window_new (GTK_WINDOW_TOPLEVEL);
    g_signal_connect (window, "delete-event",
                      G_CALLBACK (window_delete_event_handler), NULL);
    gtk_widget_show (window);

    gtk_main ();
}

static void
task_entry2 (void *data)
{
    YumeTask *task1 = data;

    for (; yume_task_get_state (task1) != YUME_TASK_STOPPED;) {
        printf ("hello 1\n");
        yume_task_sleep (1000);
    }

    yume_task_unref (task1);
}

int
main (int argc, char *argv[])
{
    YumeTask *task1, *task2;

    yume_task_system_init ();

    task1 = yume_task_new (1024 * 1024);
    yume_task_set_priority (task1, 2);
    yume_task_run (task1, task_entry1, NULL);

    task2 = yume_task_new (-1);
    yume_task_set_priority (task2, 1);
    yume_task_run (task2, task_entry2, yume_task_ref (task1));

    yume_task_system_run ();

    yume_task_system_fini ();

    return 0;
}
